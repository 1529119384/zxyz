package uno.acloud.im.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.ConversationType;
import uno.acloud.im.infrastructure.persistence.entity.ImConversation;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImEntityMapper;
import uno.acloud.im.dto.CreateProjectConversationRequest;
import uno.acloud.im.vo.ProjectConversationVO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectConversationServiceTest {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private TeamNotificationConversationService teamNotificationConversationService;
    @Mock
    private ImEntityMapper imEntityMapper;

    private ProjectConversationService service;

    @BeforeEach
    void setUp() {
        service = new ProjectConversationService(conversationMapper, teamNotificationConversationService, imEntityMapper);
    }

    @Test
    void createOrGetShouldPersistProjectNameWhenCreatingConversation() {
        when(imEntityMapper.toConversationVO(any())).thenAnswer(invocation -> {
            ImConversation c = invocation.getArgument(0);
            return new ProjectConversationVO(
                    c.getId(), c.getProjectId(), c.getTeamId(),
                    c.getName(), c.getType(), c.getReadOnly()
            );
        });
        CreateProjectConversationRequest request = projectRequest("  研发项目  ");
        when(conversationMapper.getConversationByBizKey("PROJECT:31")).thenReturn(null);
        doAnswer(invocation -> {
            ImConversation conversation = invocation.getArgument(0);
            conversation.setId(88L);
            return 1;
        }).when(conversationMapper).insertConversation(any(ImConversation.class));

        ProjectConversationVO result = service.createOrGet(request);

        ArgumentCaptor<ImConversation> conversationCaptor = ArgumentCaptor.forClass(ImConversation.class);
        verify(conversationMapper).insertConversation(conversationCaptor.capture());
        ImConversation inserted = conversationCaptor.getValue();
        assertEquals(ConversationType.PROJECT, inserted.getType());
        assertEquals(31L, inserted.getProjectId());
        assertEquals(6L, inserted.getTeamId());
        assertEquals("研发项目", inserted.getName());
        assertFalse(inserted.getReadOnly());
        verify(conversationMapper).upsertConversationMember(88L, 7L);
        verify(conversationMapper, never()).updateProjectConversationName(any(), any());
        assertEquals(88L, result.getConversationId());
        assertEquals("研发项目", result.getName());
    }

    @Test
    void createOrGetShouldSyncProjectNameWhenConversationAlreadyExists() {
        when(imEntityMapper.toConversationVO(any())).thenAnswer(invocation -> {
            ImConversation c = invocation.getArgument(0);
            return new ProjectConversationVO(
                    c.getId(), c.getProjectId(), c.getTeamId(),
                    c.getName(), c.getType(), c.getReadOnly()
            );
        });
        ImConversation existing = new ImConversation();
        existing.setId(88L);
        existing.setType(ConversationType.PROJECT);
        existing.setProjectId(31L);
        existing.setTeamId(6L);
        existing.setName("旧团队名");
        existing.setReadOnly(Boolean.FALSE);
        when(conversationMapper.getConversationByBizKey("PROJECT:31")).thenReturn(existing);

        ProjectConversationVO result = service.createOrGet(projectRequest("研发项目"));

        verify(conversationMapper).updateProjectConversationName(88L, "研发项目");
        verify(conversationMapper).upsertConversationMember(88L, 7L);
        verify(conversationMapper, never()).insertConversation(any(ImConversation.class));
        assertEquals("研发项目", existing.getName());
        assertEquals("研发项目", result.getName());
    }

    @Test
    void createOrGetShouldRejectBlankProjectName() {
        CreateProjectConversationRequest request = projectRequest(" ");

        BusinessException exception = assertThrows(BusinessException.class, () -> service.createOrGet(request));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        verifyNoInteractions(conversationMapper);
    }

    private CreateProjectConversationRequest projectRequest(String name) {
        CreateProjectConversationRequest request = new CreateProjectConversationRequest();
        request.setProjectId(31L);
        request.setTeamId(6L);
        request.setLeaderUserId(7L);
        request.setName(name);
        return request;
    }
}
