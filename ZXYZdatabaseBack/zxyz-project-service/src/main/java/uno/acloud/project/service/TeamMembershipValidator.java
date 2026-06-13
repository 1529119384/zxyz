package uno.acloud.project.service;

/**
 * 团队成员身份校验合同，供用户等非文件领域服务复用最小成员校验能力。
 */
public interface TeamMembershipValidator {

    void requireTeamMember(Long teamId, Long userId);
}
