package uno.acloud.team.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.team.entity.Team;
import uno.acloud.team.mapper.TeamMapper;
import uno.acloud.team.mapper.TeamPermissionMapper;
import uno.acloud.team.service.impl.TeamPermissionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TeamUserCleanupService {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:deleted:team:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    private final TeamMapper teamMapper;
    private final TeamPermissionManager teamPermissionManager;
    private final TeamPermissionMapper teamPermissionMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    public TeamUserCleanupService(TeamMapper teamMapper,
                                  TeamPermissionManager teamPermissionManager,
                                  TeamPermissionMapper teamPermissionMapper,
                                  org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        this.teamMapper = teamMapper;
        this.teamPermissionManager = teamPermissionManager;
        this.teamPermissionMapper = teamPermissionMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 移除用户在所有团队的成员关系，并对「用户是团队所有者」的情况给出明确终态：
     * 有继任者则把所有权转让给继任者，无继任者则解散团队（team.status = 2），
     * 避免留下「列表里看起来正常、但没有任何活人能管理」的无主僵尸团队。
     * <p>
     * 事务取舍：方法级事务保证单个团队内「改 owner + 改角色 + 移除成员」的多表更新是原子的；
     * 但循环内 catch 会吞掉异常并计入 failed，所以单个团队失败既不会回滚其它团队，
     * 也不会让 MQ 整体重试 —— 清理是尽力而为，个别失败由汇总日志与告警暴露。
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeUserFromTeams(long userId) {
        List<Long> teamIds = teamMapper.listMyTeams(userId).stream()
                .map(Team::getId)
                .toList();

        int removed = 0;
        int transferred = 0;
        int dissolved = 0;
        int failed = 0;
        for (Long teamId : teamIds) {
            try {
                Team team = teamMapper.selectById(teamId);
                if (!isOwner(team, userId)) {
                    // 普通成员：置为已移除即可，团队归属不受影响
                    if (teamMapper.removeMember(teamId, userId) > 0) {
                        removed++;
                    }
                    continue;
                }

                LocalDateTime now = LocalDateTime.now();
                Long successor = teamMapper.selectSuccessorOwner(teamId, userId);
                if (successor != null) {
                    // 有继任者：先改 team.owner_user_id，再把继任者的角色提升为 owner，最后摘掉注销用户
                    teamMapper.transferOwner(teamId, userId, successor, now);
                    teamMapper.updateMemberRoleLabel(teamId, successor, TeamRoleCodes.OWNER);
                    syncOwnerRoleToRbac(teamId, successor);
                    teamMapper.removeMember(teamId, userId);
                    transferred++;
                    log.info("团队所有者已注销，所有权自动转让: teamId={}, fromUserId={}, toUserId={}",
                            teamId, userId, successor);
                } else {
                    // 无继任者：团队已无人可接手，只能解散，否则会成为无主僵尸团队
                    teamMapper.dissolveTeam(teamId, userId, now);
                    teamMapper.removeMember(teamId, userId);
                    dissolved++;
                    log.info("团队所有者已注销且无继任者，团队已解散: teamId={}, userId={}", teamId, userId);
                }
            } catch (Exception e) {
                log.error("清理用户团队关系失败: userId={}, teamId={}", userId, teamId, e);
                failed++;
            }
        }
        log.info("移除用户团队成员关系完成: userId={}, removed={}, transferred={}, dissolved={}, failed={}",
                userId, removed, transferred, dissolved, failed);
    }

    private static boolean isOwner(Team team, long userId) {
        return team != null && Long.valueOf(userId).equals(team.getOwnerUserId());
    }

    /**
     * 把继任者的 owner 角色同步到 team_member_role（RBAC 明细表）。
     * team_member.role_code 只是反范式冗余字段，真正的权限判定走 team_member_role，
     * 不同步的话继任者「名义上是 owner，却没有 owner 的管理权限」。
     * <p>
     * 这里刻意不用 RoleManagementService#grantBuiltInRole：它会走 repairBuiltInRoles，
     * 把本团队所有内置角色的权限绑定重置一遍，可能抹掉管理员对内置角色的自定义授权。
     * TeamPermissionManager#assignMemberRole 只增删成员的角色绑定，副作用最小。
     * <p>
     * 该方法参与外层事务，一旦抛异常会把外层事务标记成 rollback-only，
     * 导致整个清理在提交时失败并被 MQ 无限重试，所以先做前置校验再 try/catch 兜底：
     * 角色同步失败只记日志，不阻断已经完成的所有权转让。
     */
    private void syncOwnerRoleToRbac(Long teamId, Long successorUserId) {
        try {
            if (teamPermissionMapper.getRoleId(teamId, TeamRoleCodes.OWNER) == null) {
                log.error("团队缺少内置 owner 角色定义，跳过 team_member_role 同步（需人工核对）: teamId={}, userId={}",
                        teamId, successorUserId);
                return;
            }
            teamPermissionManager.assignMemberRole(teamId, successorUserId, TeamRoleCodes.OWNER);
        } catch (Exception e) {
            log.error("同步继任者 owner 角色到 team_member_role 失败（所有权已转让，需人工核对）: teamId={}, userId={}",
                    teamId, successorUserId, e);
        }
    }

    public boolean tryAcquireIdempotencyKey(long userId) {
        String key = IDEMPOTENCY_KEY_PREFIX + userId;
        return redisTemplate.opsForValue().setIfAbsent(key, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS);
    }

    public void releaseIdempotencyKey(long userId) {
        String key = IDEMPOTENCY_KEY_PREFIX + userId;
        redisTemplate.delete(key);
    }
}
