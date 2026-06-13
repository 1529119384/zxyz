package uno.acloud.project.service;

import uno.acloud.project.dto.project.AddProjectMemberRequest;
import uno.acloud.project.dto.project.TransferProjectLeaderRequest;
import uno.acloud.project.vo.project.ProjectMemberVO;
import uno.acloud.project.vo.project.ProjectVO;

import java.util.List;

public interface ProjectMemberPort {

    List<ProjectMemberVO> listMembers(Long projectId, Long userId);

    ProjectMemberVO addMember(Long projectId, AddProjectMemberRequest request, Long operatorUserId);

    ProjectVO transferLeader(Long projectId, TransferProjectLeaderRequest request, Long operatorUserId);
}
