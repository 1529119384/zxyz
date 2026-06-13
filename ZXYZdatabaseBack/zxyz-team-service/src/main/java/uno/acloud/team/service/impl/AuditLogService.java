package uno.acloud.team.service.impl;

import org.springframework.stereotype.Service;
import uno.acloud.team.entity.PermissionAuditEntity;
import uno.acloud.team.entity.TeamPermissionAudit;
import uno.acloud.team.mapper.PermissionRoleMapper;
import uno.acloud.team.mapper.TeamPermissionMapper;

import java.time.LocalDateTime;

@Service
public class AuditLogService {

    private final TeamPermissionMapper teamPermissionMapper;
    private final PermissionRoleMapper permissionRoleMapper;

    public AuditLogService(TeamPermissionMapper teamPermissionMapper,
                           PermissionRoleMapper permissionRoleMapper) {
        this.teamPermissionMapper = teamPermissionMapper;
        this.permissionRoleMapper = permissionRoleMapper;
    }

    public void writeTeamAudit(Long teamId, Long operatorId, String operationType, String targetType,
                               Long targetId, String beforeValue, String afterValue) {
        TeamPermissionAudit audit = new TeamPermissionAudit();
        audit.setTeamId(teamId);
        audit.setOperatorId(operatorId);
        audit.setOperationType(operationType);
        audit.setTargetType(targetType);
        audit.setTargetId(targetId);
        audit.setBeforeValue(beforeValue);
        audit.setAfterValue(afterValue);
        audit.setOperationTime(LocalDateTime.now());
        teamPermissionMapper.insertAudit(audit);
    }

    public void writeSystemAudit(Long operatorId, String scopeType, String operationType, String targetType,
                                 Long targetId, String beforeValue, String afterValue, String ipAddress) {
        PermissionAuditEntity audit = new PermissionAuditEntity();
        audit.setOperatorId(operatorId);
        audit.setScopeType(scopeType);
        audit.setOperationType(operationType);
        audit.setTargetType(targetType);
        audit.setTargetId(targetId);
        audit.setBeforeValue(beforeValue);
        audit.setAfterValue(afterValue);
        audit.setOperationTime(LocalDateTime.now());
        audit.setIpAddress(ipAddress);
        permissionRoleMapper.insertAudit(audit);
    }
}
