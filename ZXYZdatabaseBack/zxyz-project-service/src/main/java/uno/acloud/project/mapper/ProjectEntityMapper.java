package uno.acloud.project.mapper;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.project.entity.Project;
import uno.acloud.project.entity.ProjectCreateRequest;
import uno.acloud.project.entity.ProjectMember;
import uno.acloud.project.entity.RoleEntity;
import uno.acloud.project.vo.permission.RoleItemVO;
import uno.acloud.project.vo.project.ProjectCreateRequestVO;
import uno.acloud.project.vo.project.ProjectMemberVO;
import uno.acloud.project.vo.project.ProjectVO;

import java.util.List;
import java.util.Map;

/**
 * MapStruct 对象映射 — project-service 实体到 VO 的转换。
 *
 * <p>storageLimit、usedStorage、accessible、manageable 等运行时计算字段
 * 由 {@link uno.acloud.project.service.impl.ProjectViewAssembler} 在映射后补充设置。</p>
 */
@Mapper(componentModel = "spring")
public interface ProjectEntityMapper {

    // ==================== Project → ProjectVO ====================

    @Mapping(target = "storageLimit", ignore = true)
    @Mapping(target = "usedStorage", ignore = true)
    @Mapping(target = "accessible", ignore = true)
    @Mapping(target = "manageable", ignore = true)
    ProjectVO toProjectVO(Project project);

    List<ProjectVO> toProjectVOList(List<Project> projects);

    // ==================== ProjectMember → ProjectMemberVO ====================

    @Mapping(target = "username", expression = "java(resolveUsername(member, userMap))")
    @Mapping(target = "name", expression = "java(resolveName(member, userMap))")
    @Mapping(target = "avatar", expression = "java(resolveAvatar(member, userMap))")
    ProjectMemberVO toMemberVO(ProjectMember member, @Context Map<Long, UserInfoDTO> userMap);

    default String resolveUsername(ProjectMember member, @Context Map<Long, UserInfoDTO> userMap) {
        UserInfoDTO user = userMap.get(member.getUserId());
        return user != null ? user.getUsername() : null;
    }

    default String resolveName(ProjectMember member, @Context Map<Long, UserInfoDTO> userMap) {
        UserInfoDTO user = userMap.get(member.getUserId());
        return user != null ? user.getName() : null;
    }

    default String resolveAvatar(ProjectMember member, @Context Map<Long, UserInfoDTO> userMap) {
        UserInfoDTO user = userMap.get(member.getUserId());
        return user != null ? user.getAvatar() : null;
    }

    // ==================== ProjectCreateRequest → ProjectCreateRequestVO ====================

    @Mapping(target = "requesterName", expression = "java(resolveRequesterName(request, userMap))")
    @Mapping(target = "leaderName", expression = "java(resolveLeaderName(request, userMap))")
    ProjectCreateRequestVO toCreateRequestVO(ProjectCreateRequest request, @Context Map<Long, UserInfoDTO> userMap);

    default String resolveRequesterName(ProjectCreateRequest request, @Context Map<Long, UserInfoDTO> userMap) {
        return displayName(userMap.get(request.getRequesterUserId()), request.getRequesterUserId());
    }

    default String resolveLeaderName(ProjectCreateRequest request, @Context Map<Long, UserInfoDTO> userMap) {
        return displayName(userMap.get(request.getLeaderUserId()), request.getLeaderUserId());
    }

    default String displayName(UserInfoDTO user, Long userId) {
        if (user != null && user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        if (user != null && user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return userId == null ? "" : "用户 " + userId;
    }

    // ==================== RoleEntity → RoleItemVO ====================

    @Mapping(target = "permissionCodes", ignore = true)
    RoleItemVO toRoleItemVO(RoleEntity role);

    @Mapping(target = "permissionCodes", ignore = true)
    List<RoleItemVO> toRoleItemVOList(List<RoleEntity> roles);
}
