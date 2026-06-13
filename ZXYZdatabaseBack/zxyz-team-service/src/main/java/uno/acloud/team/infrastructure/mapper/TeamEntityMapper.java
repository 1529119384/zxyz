package uno.acloud.team.infrastructure.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Context;
import uno.acloud.team.entity.PermissionAuditEntity;
import uno.acloud.team.entity.PermissionEntity;
import uno.acloud.team.entity.RoleEntity;
import uno.acloud.team.entity.Team;
import uno.acloud.team.entity.TeamMember;
import uno.acloud.team.entity.TeamPermissionAudit;
import uno.acloud.team.entity.TeamPermissionEntity;
import uno.acloud.team.entity.TeamQuota;
import uno.acloud.team.entity.TeamRoleEntity;
import uno.acloud.team.vo.permission.PermissionAuditVO;
import uno.acloud.team.vo.permission.PermissionItemVO;
import uno.acloud.team.vo.permission.RoleItemVO;
import uno.acloud.team.vo.permission.TeamPermissionAuditVO;
import uno.acloud.team.vo.permission.TeamPermissionVO;
import uno.acloud.team.vo.permission.TeamRoleVO;
import uno.acloud.team.vo.team.TeamMemberDetailVO;
import uno.acloud.team.vo.team.TeamMemberVO;
import uno.acloud.team.vo.team.TeamQuotaVO;
import uno.acloud.team.vo.team.TeamVO;
import uno.acloud.dto.UserInfoDTO;

import java.util.List;
import java.util.Map;

/**
 * MapStruct 对象映射 — team-service 实体到 VO 的转换。
 *
 * <p>本接口由 MapStruct 注解处理器在编译期自动生成实现类（TeamEntityMapperImpl）。
 * Spring 通过 @Mapper(componentModel = "spring") 自动注册为 Bean。</p>
 *
 * <p>渐进迁移策略：新代码优先使用 MapStruct，旧的手动转换逐步替换。</p>
 */
@Mapper(componentModel = "spring")
public interface TeamEntityMapper {

    // ==================== Team → TeamVO ====================

    /**
     * Team 实体 → TeamVO（基础字段映射）。
     * myRoleCode 和 myPermissions 需由调用方在映射后手动设置。
     */
    @Mapping(target = "myRoleCode", ignore = true)
    @Mapping(target = "myPermissions", ignore = true)
    TeamVO toTeamVO(Team team);

    List<TeamVO> toTeamVOList(List<Team> teams);

    // ==================== TeamMember → TeamMemberVO ====================

    /**
     * TeamMember 实体 → TeamMemberVO。
     * username、name、avatar、email 来自外部 UserInfoDTO，通过 @Context 注入。
     */
    @Mapping(target = "username", expression = "java(resolveUsername(member, userMap))")
    @Mapping(target = "name", expression = "java(resolveName(member, userMap))")
    @Mapping(target = "avatar", expression = "java(resolveAvatar(member, userMap))")
    @Mapping(target = "email", expression = "java(resolveEmail(member, userMap))")
    TeamMemberVO toMemberVO(TeamMember member, @Context Map<Long, UserInfoDTO> userMap);

    /** 从 userMap 中安全提取 username */
    default String resolveUsername(TeamMember member, @Context Map<Long, UserInfoDTO> userMap) {
        UserInfoDTO user = userMap.get(member.getUserId());
        return user != null ? user.getUsername() : null;
    }

    /** 从 userMap 中安全提取 name */
    default String resolveName(TeamMember member, @Context Map<Long, UserInfoDTO> userMap) {
        UserInfoDTO user = userMap.get(member.getUserId());
        return user != null ? user.getName() : null;
    }

    /** 从 userMap 中安全提取 avatar */
    default String resolveAvatar(TeamMember member, @Context Map<Long, UserInfoDTO> userMap) {
        UserInfoDTO user = userMap.get(member.getUserId());
        return user != null ? user.getAvatar() : null;
    }

    /** 从 userMap 中安全提取 email */
    default String resolveEmail(TeamMember member, @Context Map<Long, UserInfoDTO> userMap) {
        UserInfoDTO user = userMap.get(member.getUserId());
        return user != null ? user.getEmail() : null;
    }

    // ==================== TeamMember → TeamMemberDetailVO ====================

    /** TeamMember → TeamMemberDetailVO（内部服务间调用，字段 1:1 映射） */
    TeamMemberDetailVO toMemberDetailVO(TeamMember member);

    List<TeamMemberDetailVO> toMemberDetailVOList(List<TeamMember> members);

    // ==================== TeamQuota → TeamQuotaVO ====================

    /** TeamQuota → TeamQuotaVO（内部服务间调用） */
    TeamQuotaVO toQuotaVO(TeamQuota quota);

    // ==================== PermissionEntity → PermissionItemVO ====================

    /** 系统权限定义 → PermissionItemVO */
    PermissionItemVO toPermissionItemVO(PermissionEntity entity);

    List<PermissionItemVO> toPermissionItemVOList(List<PermissionEntity> entities);

    // ==================== RoleEntity → RoleItemVO ====================

    /**
     * 系统角色 → RoleItemVO。
     * permissionCodes 由调用方在映射后手动设置（需要额外查询）。
     */
    @Mapping(target = "permissionCodes", ignore = true)
    RoleItemVO toRoleItemVO(RoleEntity role);

    List<RoleItemVO> toRoleItemVOList(List<RoleEntity> roles);

    // ==================== PermissionAuditEntity → PermissionAuditVO ====================

    /** 系统权限审计 → PermissionAuditVO */
    PermissionAuditVO toPermissionAuditVO(PermissionAuditEntity entity);

    List<PermissionAuditVO> toPermissionAuditVOList(List<PermissionAuditEntity> entities);

    // ==================== TeamPermissionEntity → TeamPermissionVO ====================

    /** 团队权限定义 → TeamPermissionVO */
    TeamPermissionVO toTeamPermissionVO(TeamPermissionEntity entity);

    List<TeamPermissionVO> toTeamPermissionVOList(List<TeamPermissionEntity> entities);

    // ==================== TeamPermissionAudit → TeamPermissionAuditVO ====================

    /** 团队权限审计 → TeamPermissionAuditVO */
    TeamPermissionAuditVO toTeamPermissionAuditVO(TeamPermissionAudit entity);

    List<TeamPermissionAuditVO> toTeamPermissionAuditVOList(List<TeamPermissionAudit> entities);

    // ==================== TeamRoleEntity → TeamRoleVO ====================

    /**
     * 团队角色 → TeamRoleVO。
     * builtin: Integer→Boolean（1=true, 其他=false），permissionCodes 由调用方设置。
     */
    @Mapping(target = "builtin", expression = "java(Integer.valueOf(1).equals(role.getBuiltin()))")
    @Mapping(target = "permissionCodes", ignore = true)
    TeamRoleVO toTeamRoleVO(TeamRoleEntity role);

    List<TeamRoleVO> toTeamRoleVOList(List<TeamRoleEntity> roles);
}
