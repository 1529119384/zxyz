package uno.acloud.im.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uno.acloud.im.application.ConversationReadService;
import uno.acloud.im.application.ConversationService;
import uno.acloud.im.application.DirectConversationService;
import uno.acloud.im.application.FileCardMessageService;
import uno.acloud.im.application.ImMessageService;
import uno.acloud.im.application.ImRealtimePushService;
import uno.acloud.im.application.MessageModerationService;
import uno.acloud.im.config.ImAuthContext;
import uno.acloud.im.domain.enums.MessageType;
import uno.acloud.im.dto.CreateDirectConversationRequest;
import uno.acloud.im.dto.MessageFileCardResolveRequest;
import uno.acloud.im.dto.RecallMessageRequest;
import uno.acloud.im.dto.UpdateConversationReadRequest;
import uno.acloud.im.vo.ConversationReadVO;
import uno.acloud.im.vo.ConversationSummaryVO;
import uno.acloud.im.vo.FileCardResolveVO;
import uno.acloud.im.vo.ImMessageVO;
import uno.acloud.im.vo.MessageRecallVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImControllerSplitTest {

    private static final Long CURRENT_USER_ID = 7L;

    @Mock
    private ConversationService conversationService;
    @Mock
    private DirectConversationService directConversationService;
    @Mock
    private ConversationReadService conversationReadService;
    @Mock
    private ImRealtimePushService realtimePushService;
    @Mock
    private ImMessageService imMessageService;
    @Mock
    private MessageModerationService moderationService;
    @Mock
    private FileCardMessageService fileCardMessageService;

    @BeforeEach
    void setUpAuthContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ImAuthContext.USER_ID_ATTRIBUTE, CURRENT_USER_ID);
        // 模拟鉴权拦截器写入的当前用户，避免 Controller 测试依赖 Web 容器。
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearAuthContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void conversationControllerShouldDelegateDirectConversationAndPushReadState() {
        ConversationController controller = new ConversationController(
                conversationService,
                directConversationService,
                conversationReadService,
                realtimePushService
        );
        ConversationSummaryVO summary = new ConversationSummaryVO(
                12L,
                "DIRECT",
                3L,
                null,
                "私聊",
                null,
                0,
                9L,
                "target",
                "目标用户",
                null,
                LocalDateTime.now()
        );
        CreateDirectConversationRequest createRequest = new CreateDirectConversationRequest();
        createRequest.setTeamId(3L);
        createRequest.setTargetUserId(9L);
        when(directConversationService.createOrGet(CURRENT_USER_ID, 3L, 9L)).thenReturn(summary);

        assertSame(summary, controller.createDirectConversation(createRequest).getData());

        ConversationReadVO readState = new ConversationReadVO(12L, CURRENT_USER_ID, 40L);
        List<Long> memberUserIds = List.of(CURRENT_USER_ID, 9L);
        UpdateConversationReadRequest readRequest = new UpdateConversationReadRequest();
        readRequest.setLastReadMessageId(40L);
        when(conversationReadService.updateReadPosition(CURRENT_USER_ID, 12L, 40L))
                .thenReturn(new ConversationReadService.UpdateReadResult(readState, memberUserIds));

        assertSame(readState, controller.updateReadPosition(12L, readRequest).getData());
        verify(realtimePushService).pushReadUpdated(memberUserIds, readState);
    }

    @Test
    void messageControllerShouldDelegateMessageQueries() {
        MessageController controller = new MessageController(imMessageService, moderationService, realtimePushService);
        LocalDateTime afterTime = LocalDateTime.of(2026, 4, 28, 1, 0);
        List<ImMessageVO> messages = List.of(new ImMessageVO(
                101L,
                12L,
                CURRENT_USER_ID,
                "sender",
                "发送人",
                null,
                MessageType.TEXT,
                "消息内容",
                List.of(),
                null,
                "client-1",
                "STORED",
                null,
                null,
                null,
                false,
                0,
                afterTime
        ));
        when(imMessageService.listMessages(CURRENT_USER_ID, 12L, 5L, afterTime, null, 30)).thenReturn(messages);
        when(imMessageService.searchMessages(CURRENT_USER_ID, 12L, "需求", 20)).thenReturn(messages);

        assertSame(messages, controller.listMessages(12L, 5L, afterTime, null, 30).getData());
        assertSame(messages, controller.searchMessages(12L, "需求", 20).getData());
    }

    @Test
    void messageControllerShouldPushAfterRecall() {
        MessageController controller = new MessageController(imMessageService, moderationService, realtimePushService);
        RecallMessageRequest request = new RecallMessageRequest();
        request.setReason("误发");
        MessageRecallVO recall = new MessageRecallVO(
                101L,
                12L,
                CURRENT_USER_ID,
                LocalDateTime.of(2026, 4, 28, 1, 5),
                "误发"
        );
        List<Long> memberUserIds = List.of(CURRENT_USER_ID, 9L);
        when(moderationService.recall(CURRENT_USER_ID, 101L, request))
                .thenReturn(new MessageModerationService.RecallResult(recall, memberUserIds));

        assertSame(recall, controller.recallMessage(101L, request).getData());
        verify(realtimePushService).pushMessageRecalled(memberUserIds, recall);
    }

    @Test
    void fileCardControllerShouldResolveByPathOrRequestMessageId() {
        FileCardController controller = new FileCardController(fileCardMessageService);
        FileCardResolveVO pathResult = new FileCardResolveVO(
                "AVAILABLE",
                "SINGLE_FILE",
                "设计文档",
                null,
                null,
                "/download/101",
                List.of(),
                List.of()
        );
        FileCardResolveVO requestResult = new FileCardResolveVO(
                "AVAILABLE",
                "SINGLE_FILE",
                "覆盖文件",
                null,
                null,
                "/download/202",
                List.of(),
                List.of()
        );
        when(fileCardMessageService.resolveFileCardMessage(CURRENT_USER_ID, 101L)).thenReturn(pathResult);
        when(fileCardMessageService.resolveFileCardMessage(CURRENT_USER_ID, 202L)).thenReturn(requestResult);

        assertSame(pathResult, controller.resolveFileCard(101L, null).getData());

        MessageFileCardResolveRequest request = new MessageFileCardResolveRequest();
        request.setMessageId(202L);
        assertSame(requestResult, controller.resolveFileCard(101L, request).getData());

        verify(fileCardMessageService).resolveFileCardMessage(CURRENT_USER_ID, 101L);
        verify(fileCardMessageService).resolveFileCardMessage(CURRENT_USER_ID, 202L);
    }
}
