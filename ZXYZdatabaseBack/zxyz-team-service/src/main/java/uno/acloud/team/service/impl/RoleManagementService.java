package uno.acloud.team.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.TeamPermissionPolicy;
import uno.acloud.common.util.BatchPermissionHelper;
import uno.acloud.exception.BusinessException;
import uno.acloud.satoken.PermissionCache;
import uno.acloud.team.dto.permission.AssignTeamMemberRoleRequest;
import uno.acloud.team.dto.permission.AssignTeamRolePermissionsRequest;
import uno.acloud.team.dto.permission.SaveTeamRoleRequest;
import uno.acloud.team.entity.TeamPermissionEntity;
import uno.acloud.team.entity.TeamRoleEntity;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.infrastructure.mapper.TeamEntityMapper;
import uno.acloud.team.mapper.TeamMapper;
import uno.acloud.team.mapper.TeamPermissionMapper;
import uno.acloud.team.vo.permission.TeamRoleVO;

import java.util.List;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

/**
 * 团队角色管理服务
 * 负责团队角色 CRUD、成员角色分配、内置角色初始化
 */
@Slf4j
@Service
public class RoleManagementService {

    private final TeamPermissionMapper teamPermissionMapper;
    private final TeamMapper teamMapper;
    private final TeamPermissionCacheService teamPermissionCacheService;
    private final UserServiceClient userServiceClient;
    private final AuditLogService auditLogService;
    private final TeamEntityMapper teamEntityMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public RoleManagementService(TeamPermissionMapper teamPermissionMapper,
                                 TeamMapper teamMapper,
                                 TeamPermissionCacheService teamPermissionCacheService,
                                 UserServiceClient userServiceClient,
                                 AuditLogService auditLogService,
                                 TeamEntityMapper teamEntityMapper,
                                 StringRedisTemplate stringRedisTemplate) {
        this.teamPermissionMapper = teamPermissionMapper;
        this.teamMapper = teamMapper;
        this.teamPermissionCacheService = teamPermissionCacheService;
        this.userServiceClient = userServiceClient;
        this.auditLogService = auditLogService;
        this.teamEntityMapper = teamEntityMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // ==================== 初始化 ====================

    /** 初始化团队内置角色（owner/admin/member）并分配权限 */
    @Transactional(rollbackFor = Exception.class)
    public void initializeBuiltInRoles(Long teamId, Long ownerUserId) {
        repairBuiltInRoles(teamId);
        assignMemberRoleInternal(teamId, ownerUserId, "team_owner");
    }

    // ==================== 角色 CRUD ====================

    /** 列出团队所有角色（含权限 code） */
    public List<TeamRoleVO> listRoles(Long teamId) {
        return listRolesInternal(teamId);
    }

    /** 创建或更新角色 */
    @Transactional(rollbackFor = Exception.class)
    public TeamRoleVO saveRole(Long teamId, Long roleId, SaveTeamRoleRequest request, Long operatorUserId) {
        String roleCode = requireText(request == null ? null : request.getRoleCode(), "roleCode 不能为空");
        String roleName = requireText(request == null ? null : request.getRoleName(), "roleName 不能为空");
        String description = optionalText(request == null ? null : request.getDescription());
        TeamRoleEntity role = roleId == null ? null : teamPermissionMapper.getRoleById(teamId, roleId);
        if (role == null) {
            if (teamPermissionMapper.getRoleByCode(teamId, roleCode) != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "角色编码已存在");
            }
            role = new TeamRoleEntity();
            role.setTeamId(teamId);
            role.setRoleCode(roleCode);
            role.setRoleName(roleName);
            role.setDescription(description);
            role.setBuiltin(0);
            teamPermissionMapper.insertRole(role);
            auditLogService.writeTeamAudit(teamId, operatorUserId, "team_role:create", "team_role", role.getId(), null, roleCode);
        } else {
            if (Integer.valueOf(1).equals(role.getBuiltin())) {
                throw new BusinessException(TeamErrorCode.TEAM_PERMISSION_DENIED.getCode(), "内置角色不允许修改编码");
            }
            role.setRoleName(roleName);
            role.setDescription(description);
            teamPermissionMapper.updateRole(role);
            auditLogService.writeTeamAudit(teamId, operatorUserId, "team_role:update", "team_role", role.getId(), role.getRoleCode(), role.getRoleCode());
        }
        Long savedRoleId = role.getId();
        teamPermissionCacheService.evictTeam(teamId);
        return listRolesInternal(teamId).stream().filter(item -> item.getId().equals(savedRoleId)).findFirst().orElse(null);
    }

    /** 删除非内置角色 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long teamId, Long roleId, Long operatorUserId) {
        TeamRoleEntity role = requireRole(teamId, roleId);
        if (Integer.valueOf(1).equals(role.getBuiltin())) {
            throw new BusinessException(TeamErrorCode.TEAM_PERMISSION_DENIED.getCode(), "内置角色不允许删除");
        }
        teamPermissionMapper.deleteRolePermissions(teamId, roleId);
        teamPermissionMapper.deleteRole(teamId, roleId);
        teamPermissionCacheService.evictTeam(teamId);
        auditLogService.writeTeamAudit(teamId, operatorUserId, "team_role:delete", "team_role", roleId, role.getRoleCode(), null);
    }

    // ==================== 成员角色分配 ====================

    /** 分配成员角色 */
    @Transactional(rollbackFor = Exception.class)
    public void assignMemberRole(Long teamId, AssignTeamMemberRoleRequest request, Long operatorUserId) {
        Long userId = request == null ? null : request.getUserId();
        if (userId == null || userId < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 不能为空");
        }
        if (teamMapper.getActiveMember(teamId, userId) == null) {
            throw new BusinessException(TeamErrorCode.TEAM_NOT_FOUND.getCode(), "用户不在团队中");
        }
        String beforeRole = getMemberRoleCode(teamId, userId);
        assignMemberRoleInternal(teamId, userId, requireText(request.getRoleCode(), "roleCode 不能为空"));
        auditLogService.writeTeamAudit(teamId, operatorUserId, "team_member:assign-role", "team_member", Long.valueOf(userId), beforeRole, request.getRoleCode());
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

    /** 获取成员角色 code */
    public String getMemberRoleCode(Long teamId, Long userId) {
        return teamPermissionMapper.getMemberRoleCode(teamId, userId);
    }

    /** 授权内置角色 */
    @Transactional(rollbackFor = Exception.class)
    public void grantBuiltInRole(Long teamId, Long userId, String roleCode) {
        repairBuiltInRoles(teamId);
        assignMemberRoleInternal(teamId, userId, roleCode);
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

    /** 清除成员角色 */
    @Transactional(rollbackFor = Exception.class)
    public void clearMemberRole(Long teamId, Long userId) {
        teamPermissionMapper.deleteMemberRoles(teamId, userId);
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

    // ==================== 私有方法 ====================

    private void repairBuiltInRoles(Long teamId) {
        ensureBuiltInPermissions();
        ensureBuiltInRoles(teamId);
        assignBuiltInRolePermissions(teamId);
    }

    private void ensureBuiltInPermissions() {
        List<TeamPermissionMapper.TeamPermissionSeed> seeds = TeamPermissionPolicy.builtInPermissions().stream()
                .map(permission -> {
                    TeamPermissionMapper.TeamPermissionSeed seed = new TeamPermissionMapper.TeamPermissionSeed();
                    seed.permissionName = permission.permissionName();
                    seed.permissionCode = permission.permissionCode();
                    seed.description = permission.description();
                    return seed;
                })
                .toList();
        if (!seeds.isEmpty()) {
            teamPermissionMapper.batchUpsertPermissions(seeds);
        }
    }

    private void ensureBuiltInRoles(Long teamId) {
        for (TeamPermissionPolicy.RoleDefinition role : TeamPermissionPolicy.builtInRoles()) {
            ensureRole(teamId, role.roleCode(), role.roleName(), role.description());
        }
    }

    private void assignBuiltInRolePermissions(Long teamId) {
        for (TeamPermissionPolicy.RoleDefinition role : TeamPermissionPolicy.builtInRoles()) {
            TeamRoleEntity entity = teamPermissionMapper.getRoleByCode(teamId, role.roleCode());
            if (entity == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "团队角色不存在");
            }
            assignRolePermissionsInternal(teamId, entity.getId(), TeamPermissionPolicy.permissionCodesForRole(role.roleCode()));
        }
    }

    private void ensureRole(Long teamId, String roleCode, String roleName, String description) {
        if (teamPermissionMapper.getRoleByCode(teamId, roleCode) != null) {
            return;
        }
        TeamRoleEntity role = new TeamRoleEntity();
        role.setTeamId(teamId);
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        role.setDescription(description);
        role.setBuiltin(1);
        teamPermissionMapper.insertRole(role);
    }

    void assignRolePermissionsInternal(Long teamId, Long roleId, List<String> permissionCodes) {
        teamPermissionMapper.deleteRolePermissions(teamId, roleId);
        List<String> safeCodes = permissionCodes == null ? List.of() : permissionCodes;
        List<Integer> permissionIds = BatchPermissionHelper.resolvePermissionIds(
                safeCodes,
                teamPermissionMapper::getPermissionIdsByCodes,
                TeamPermissionEntity::getId,
                TeamPermissionEntity::getPermissionCode
        );
        if (!permissionIds.isEmpty()) {
            teamPermissionMapper.batchInsertRolePermissions(teamId, roleId, permissionIds);
        }
        teamPermissionCacheService.evictTeam(teamId);
    }

    private void assignMemberRoleInternal(Long teamId, Long userId, String roleCode) {
        TeamRoleEntity role = teamPermissionMapper.getRoleByCode(teamId, roleCode);
        if (role == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "团队角色不存在");
        }
        teamPermissionMapper.deleteMemberRoles(teamId, userId);
        teamPermissionMapper.insertMemberRole(teamId, userId, role.getId());
        // 同步更新 team_member 表的反范式 role_code 字段
        teamMapper.updateMemberRoleLabel(teamId, userId, roleCode);
        teamPermissionCacheService.evictMember(teamId, userId);
    }

    private TeamRoleEntity requireRole(Long teamId, Long roleId) {
        TeamRoleEntity role = teamPermissionMapper.getRoleById(teamId, roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "团队角色不存在");
        }
        return role;
    }

    private List<TeamRoleVO> listRolesInternal(Long teamId) {
        return teamPermissionMapper.listRoles(teamId).stream()
                .map(role -> {
                    TeamRoleVO vo = teamEntityMapper.toTeamRoleVO(role);
                    vo.setPermissionCodes(teamPermissionMapper.listRolePermissionCodes(teamId, role.getId()));
                    return vo;
                })
                .toList();
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
