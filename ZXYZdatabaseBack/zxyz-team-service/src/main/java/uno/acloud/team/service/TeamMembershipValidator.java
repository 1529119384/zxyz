package uno.acloud.team.service;

public interface TeamMembershipValidator {

    void requireTeamMember(Long teamId, Long userId);
}
