package uno.acloud.project.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import uno.acloud.client.UserQueryClient;
import uno.acloud.project.entity.ProjectCreateRequest;
import uno.acloud.project.mapper.ProjectEntityMapper;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.mapper.ProjectQuotaMapper;
import uno.acloud.project.service.TeamFileAccessPort;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.project.vo.project.ProjectCreateRequestVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectViewAssemblerTest {

    @Mock
    private ProjectQuotaMapper projectQuotaMapper;

    @Mock
    private FileServiceClient fileServiceClient;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private UserQueryClient userQueryClient;

    @Mock
    private TeamFileAccessPort teamFileAccessService;

    @Spy
    private ProjectEntityMapper projectEntityMapper = Mappers.getMapper(ProjectEntityMapper.class);

    private ProjectViewAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ProjectViewAssembler(
                projectQuotaMapper,
                fileServiceClient,
                projectMapper,
                userQueryClient,
                teamFileAccessService,
                projectEntityMapper
        );
    }

    @Test
    void toCreateRequestVOListShouldResolveDisplayNamesInBatch() {
        ProjectCreateRequest first = createRequest(10L, 1L, 2L);
        ProjectCreateRequest second = createRequest(11L, 3L, 2L);
        when(userQueryClient.listByIds(List.of(1L, 2L, 3L))).thenReturn(List.of(
                user(1, "requester_user", "张三"),
                user(2, "leader_user", ""),
                user(3, "", "")
        ));

        List<ProjectCreateRequestVO> result = assembler.toCreateRequestVOList(List.of(first, second));

        assertEquals("张三", result.get(0).getRequesterName());
        assertEquals("leader_user", result.get(0).getLeaderName());
        assertEquals("用户 3", result.get(1).getRequesterName());
        assertEquals("leader_user", result.get(1).getLeaderName());
        verify(userQueryClient).listByIds(List.of(1L, 2L, 3L));
    }

    @Test
    void toCreateRequestVOListShouldSkipUserQueryWhenEmpty() {
        assertTrue(assembler.toCreateRequestVOList(List.of()).isEmpty());
    }

    private ProjectCreateRequest createRequest(Long id, Long requesterUserId, Long leaderUserId) {
        ProjectCreateRequest createRequest = new ProjectCreateRequest();
        createRequest.setId(id);
        createRequest.setTeamId(100L);
        createRequest.setRequesterUserId(requesterUserId);
        createRequest.setProjectName("项目" + id);
        createRequest.setDescription("说明" + id);
        createRequest.setLeaderUserId(leaderUserId);
        createRequest.setStatus(0);
        return createRequest;
    }

    private UserInfoDTO user(long id, String username, String name) {
        UserInfoDTO dto = new UserInfoDTO();
        dto.setId(id);
        dto.setUsername(username);
        dto.setName(name);
        return dto;
    }
}
