package uno.acloud.team.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamPermissionPolicy;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.satoken.PermissionCache;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.mapper.TeamPermissionMapper;

import java.util.List;

@Slf4j
@Component
public class TeamPermissionManager {

    private final TeamPermissionMapper mapper;
    private final TeamPermissionCacheService teamPermissionCacheService;
    private final UserServiceClient userServiceClient;
    private final StringRedisTemplate stringRedisTemplate;

    public TeamPermissionManager(TeamPermissionMapper mapper,
                                 TeamPermissionCacheService teamPermissionCacheService,
                                 UserServiceClient userServiceClient,
                                 StringRedisTemplate stringRedisTemplate) {
        this.mapper = mapper;
        this.teamPermissionCacheService = teamPermissionCacheService;
        this.userServiceClient = userServiceClient;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void initializeBuiltInRoles(Long teamId, Long ownerUserId) {
        ensureBuiltInPermissions();
        ensureBuiltInRoles(teamId);
        assignBuiltInRolePermissions(teamId);
        assignMemberRole(teamId, ownerUserId, TeamRoleCodes.OWNER);
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignMemberRole(Long teamId, Long userId, String roleCode) {
        Long roleId = mapper.getRoleId(teamId, roleCode);
        if (roleId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "团队角色不存在");
        }
        mapper.deleteMemberRoles(teamId, userId);
        mapper.insertMemberRole(teamId, userId, roleId);
        teamPermissionCacheService.evictMember(teamId, userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    userServiceClient.clearPermissionCache(userId);
                } catch (Exception e) {
                    log.warn("Failed to clear permission cache for user {}", userId, e);
                }
                publishPermissionInvalidation(userId);
            }
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearMemberRole(Long teamId, Long userId) {
        mapper.deleteMemberRoles(teamId, userId);
        teamPermissionCacheService.evictMember(teamId, userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    userServiceClient.clearPermissionCache(userId);
                } catch (Exception e) {
                    log.warn("Failed to clear permission cache for user {}", userId, e);
                }
                publishPermissionInvalidation(userId);
            }
        });
    }

    public List<String> listRoleCodes(Long teamId, Long userId) {
        if (teamId == null || userId == null) {
            return List.of();
        }
        repairBuiltInRoles(teamId);
        return mapper.getTeamRoleCodes(userId, teamId);
    }

    public List<String> listPermissionCodes(Long teamId, Long userId) {
        if (teamId == null || userId == null) {
            return List.of();
        }
        repairBuiltInRoles(teamId);
        return mapper.getTeamPermissionCodes(userId, teamId);
    }

    private void repairBuiltInRoles(Long teamId) {
        if (mapper.getRoleId(teamId, TeamRoleCodes.OWNER) != null) {
            return;
        }
        ensureBuiltInPermissions();
        ensureBuiltInRoles(teamId);
        assignBuiltInRolePermissions(teamId);
    }

    private void ensureRole(Long teamId, String roleCode, String roleName, String description) {
        TeamPermissionMapper.TeamRoleSeed role = new TeamPermissionMapper.TeamRoleSeed();
        role.teamId = teamId;
        role.roleCode = roleCode;
        role.roleName = roleName;
        role.description = description;
        mapper.upsertRole(role);
    }

    private void ensureBuiltInPermissions() {
        for (TeamPermissionPolicy.PermissionDefinition permission : TeamPermissionPolicy.builtInPermissions()) {
            TeamPermissionMapper.TeamPermissionSeed seed = new TeamPermissionMapper.TeamPermissionSeed();
            seed.permissionName = permission.permissionName();
            seed.permissionCode = permission.permissionCode();
            seed.description = permission.description();
            mapper.upsertPermission(seed);
        }
    }

    private void ensureBuiltInRoles(Long teamId) {
        for (TeamPermissionPolicy.RoleDefinition role : TeamPermissionPolicy.builtInRoles()) {
            ensureRole(teamId, role.roleCode(), role.roleName(), role.description());
        }
    }

    private void assignBuiltInRolePermissions(Long teamId) {
        for (TeamPermissionPolicy.RoleDefinition role : TeamPermissionPolicy.builtInRoles()) {
            assignPermissions(teamId, role.roleCode(), TeamPermissionPolicy.permissionCodesForRole(role.roleCode()));
        }
    }

    private void assignPermissions(Long teamId, String roleCode, List<String> permissionCodes) {
        Long roleId = mapper.getRoleId(teamId, roleCode);
        if (roleId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "团队角色不存在");
        }
        mapper.deleteRolePermissions(teamId, roleId);
        for (String permissionCode : permissionCodes) {
            Integer permissionId = mapper.getPermissionId(permissionCode);
            if (permissionId == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "团队权限不存在: " + permissionCode);
            }
            mapper.insertRolePermission(teamId, roleId, permissionId);
        }
        teamPermissionCacheService.evictTeam(teamId);
    }

    /**
     * 发布用户权限/角色变更失效通知（跨节点秒级失效）。
     * 通过 Redis Pub/Sub 频道 {@link PermissionCache#INVALIDATION_TOPIC} 广播 userId，
     * 各消费服务的 {@code PermissionCache} 订阅后清除该用户本地缓存。
     * Redis 不可用时静默降级，不影响主流程（5 分钟 TTL 兜底）。
     */
    private void publishPermissionInvalidation(Long userId) {
        try {
            stringRedisTemplate.convertAndSend(PermissionCache.INVALIDATION_TOPIC, String.valueOf(userId));
        } catch (Exception e) {
            log.warn("Failed to publish permission invalidation for user {} after commit", userId, e);
        }
    }
}
