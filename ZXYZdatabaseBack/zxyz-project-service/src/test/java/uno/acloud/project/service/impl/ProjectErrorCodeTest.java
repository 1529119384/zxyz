package uno.acloud.project.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import static uno.acloud.common.TeamErrorCode.*;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.mapper.ProjectCreateRequestMapper;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.mapper.ProjectQuotaMapper;
import uno.acloud.project.service.TeamFileAccessPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static uno.acloud.common.ProjectErrorCode.*;

@ExtendWith(MockitoExtension.class)
class ProjectErrorCodeTest {

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectCreateRequestMapper projectCreateRequestMapper;

    @Mock
    private ProjectQuotaMapper projectQuotaMapper;

    @Mock
    private TeamServiceClient teamServiceClient;

    @Mock
    private TeamFileAccessPort teamFileAccessPort;

    private ProjectAccessGuardService projectAccessGuardService;
    private ProjectCommandSupport projectCommandSupport;

    @BeforeEach
    void setUp() {
        projectAccessGuardService = new ProjectAccessGuardService(projectMapper, teamFileAccessPort);
        projectCommandSupport = new ProjectCommandSupport(
                projectMapper,
                projectCreateRequestMapper,
                projectQuotaMapper,
                teamServiceClient
        );
    }

    @Test
    void requireProjectAccessShouldUseProjectNotFoundCode() {
        when(projectMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> projectAccessGuardService.requireProjectAccess(99L, 1L));

        assertEquals(PROJECT_NOT_FOUND.getCode(), exception.getErrorCode());
    }

    @Test
    void requireProjectCreateRequestShouldUseProjectCreateRequestNotFoundCode() {
        when(projectCreateRequestMapper.selectById(88L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> projectCommandSupport.requireProjectCreateRequest(88L));

        assertEquals(PROJECT_CREATE_REQUEST_NOT_FOUND.getCode(), exception.getErrorCode());
    }

    @Test
    void requireActiveTeamMemberShouldUsePermissionDeniedCode() {
        when(teamServiceClient.isActiveMember(10L, 2L)).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> projectCommandSupport.requireActiveTeamMember(10L, 2L));

        assertEquals(TEAM_PERMISSION_DENIED.getCode(), exception.getErrorCode());
    }
}
