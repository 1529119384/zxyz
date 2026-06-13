package uno.acloud.team.service;

import uno.acloud.common.oss.AvatarUploadSignRequest;
import uno.acloud.team.dto.team.CreateTeamMemberRequest;
import uno.acloud.team.dto.team.CreateTeamRequest;
import uno.acloud.team.dto.team.UpdateTeamMemberStatusRequest;
import uno.acloud.team.dto.team.UpdateTeamRequest;
import uno.acloud.common.oss.OssSignInfo;
import uno.acloud.team.vo.team.TeamMemberStorageVO;
import uno.acloud.team.vo.team.TeamMemberVO;
import uno.acloud.team.vo.team.TeamVO;

import java.util.List;

public interface EnterpriseTeamPort {

    TeamVO createTeam(CreateTeamRequest request);

    List<TeamVO> listMyTeams(Long userId);

    List<TeamMemberVO> listMembers(Long teamId, Long operatorUserId);

    TeamVO updateTeam(Long teamId, UpdateTeamRequest request, Long operatorUserId);

    OssSignInfo getAvatarUploadSign(Long teamId, AvatarUploadSignRequest request, Long operatorUserId);

    TeamMemberVO createMember(Long teamId, CreateTeamMemberRequest request, Long operatorUserId);

    TeamMemberVO updateMemberStatus(Long teamId,
                                    Long targetUserId,
                                    UpdateTeamMemberStatusRequest request,
                                    Long operatorUserId);

    void removeMember(Long teamId, Long targetUserId, Long operatorUserId);

    void leaveTeam(Long teamId, Long userId);

    List<TeamMemberStorageVO> listMembersStorageUsage(Long teamId, Long operatorUserId);

    void updateMemberPersonalStorageLimit(Long teamId, Long targetUserId, Long personalStorageLimit, Long operatorUserId);
}
