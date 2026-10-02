package uno.acloud.im.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.MessageType;
import uno.acloud.im.vo.ImMessageVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImCommandDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ImMessageService imMessageService;
    @Mock
    private FileCardMessageService fileCardMessageService;
    @Mock
    private ImRealtimePushService realtimePushService;

    private ImCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ImCommandDispatcher(imMessageService, fileCardMessageService, realtimePushService);
    }

    @Test
    void shouldDispatchTextMessageAndPushToMembers() throws Exception {
        ImMessageVO message = message(300L, 100L, MessageType.TEXT);
        when(imMessageService.storeTextMessage(7L, 100L, "client-1", "hello", List.of(9L, 10L)))
                .thenReturn(new ImMessageService.StoreMessageResult(300L, message, List.of(7L, 9L)));

        ImCommandResult result = dispatcher.dispatch(new ImCommandRequest(
                7L,
                "Bearer token",
                "SEND_TEXT",
                "request-1",
                "client-1",
                100L,
                payload("{\"content\":\"hello\",\"mentions\":[9,10]}")
        ));

        assertEquals("request-1", result.requestId());
        assertEquals("client-1", result.clientMessageId());
        assertEquals(100L, result.conversationId());
        assertEquals(300L, result.messageId());
        verify(realtimePushService).pushMessageReceived(List.of(7L, 9L), message);
    }

    @Test
    void shouldDispatchFileCardMessageAndPushToMembers() throws Exception {
        ImMessageVO message = message(301L, 101L, MessageType.FILE_CARD);
        when(fileCardMessageService.storeFileCardMessage(8L, 101L, "client-2", List.of(11L, 12L)))
                .thenReturn(new ImMessageService.StoreMessageResult(301L, message, List.of(8L, 10L)));

        ImCommandResult result = dispatcher.dispatch(new ImCommandRequest(
                8L,
                "Bearer token",
                "SEND_FILE_CARD",
                "request-2",
                "client-2",
                101L,
                payload("{\"fileIds\":[11,12]}")
        ));

        assertEquals("request-2", result.requestId());
        assertEquals("client-2", result.clientMessageId());
        assertEquals(101L, result.conversationId());
        assertEquals(301L, result.messageId());
        verify(realtimePushService).pushMessageReceived(List.of(8L, 10L), message);
    }

    @Test
    void shouldRejectAnonymousCommand() throws Exception {
        BusinessException exception = assertThrows(BusinessException.class, () -> dispatcher.dispatch(new ImCommandRequest(
                null,
                null,
                "SEND_TEXT",
                "request-3",
                "client-3",
                100L,
                payload("{\"content\":\"hello\"}")
        )));

        assertEquals(ErrorCode.NO_LOGIN, exception.getErrorCode());
        assertEquals("未登录", exception.getMessage());
        verifyNoInteractions(imMessageService, fileCardMessageService, realtimePushService);
    }

    @Test
    void shouldRejectMissingConversationId() throws Exception {
        BusinessException exception = assertThrows(BusinessException.class, () -> dispatcher.dispatch(new ImCommandRequest(
                7L,
                "Bearer token",
                "SEND_TEXT",
                "request-4",
                "client-4",
                null,
                payload("{\"content\":\"hello\"}")
        )));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals("conversationId 不能为空", exception.getMessage());
        verifyNoInteractions(imMessageService, fileCardMessageService, realtimePushService);
    }

    @Test
    void shouldRejectBlankClientMessageId() throws Exception {
        BusinessException exception = assertThrows(BusinessException.class, () -> dispatcher.dispatch(new ImCommandRequest(
                7L,
                "Bearer token",
                "SEND_TEXT",
                "request-5",
                " ",
                100L,
                payload("{\"content\":\"hello\"}")
        )));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals("clientMessageId 不能为空", exception.getMessage());
        verifyNoInteractions(imMessageService, fileCardMessageService, realtimePushService);
    }

    @Test
    void shouldRejectUnsupportedCommandType() throws Exception {
        BusinessException exception = assertThrows(BusinessException.class, () -> dispatcher.dispatch(new ImCommandRequest(
                7L,
                "Bearer token",
                "SEND_IMAGE",
                "request-6",
                "client-6",
                100L,
                payload("{}")
        )));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        // 异常消息经 GlobalExceptionHandler → Result 原样下发给 WS 客户端，
        // 不得回显用户可控的 type（否则可据此探测服务端支持面）。
        assertFalse(exception.getMessage().contains("SEND_IMAGE"),
                "异常消息不得回显 type，实际：" + exception.getMessage());
        verifyNoInteractions(imMessageService, fileCardMessageService, realtimePushService);
    }

    @Test
    void shouldNotEchoHostileTypeIntoClientVisibleMessage() throws Exception {
        String hostileType = "<script>alert(1)</script>";
        BusinessException exception = assertThrows(BusinessException.class, () -> dispatcher.dispatch(new ImCommandRequest(
                7L,
                "Bearer token",
                hostileType,
                "request-6b",
                "client-6b",
                100L,
                payload("{}")
        )));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertFalse(exception.getMessage().contains("script"),
                "用户可控的 type 不得出现在客户端可见的异常消息里，实际：" + exception.getMessage());
        verifyNoInteractions(imMessageService, fileCardMessageService, realtimePushService);
    }

    // ==================== payload 数组元素上限 ====================

    @Test
    void shouldRejectMentionsListExceedingElementLimit() throws Exception {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> dispatcher.dispatch(textCommandWithMentions(101)));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        verifyNoInteractions(imMessageService, fileCardMessageService, realtimePushService);
    }

    @Test
    void shouldAcceptMentionsListAtExactElementLimit() throws Exception {
        when(imMessageService.storeTextMessage(eq(7L), eq(100L), eq("client-limit"), eq("hello"), anyList()))
                .thenReturn(new ImMessageService.StoreMessageResult(
                        300L, message(300L, 100L, MessageType.TEXT), List.of(7L)));

        ImCommandResult result = dispatcher.dispatch(textCommandWithMentions(100));

        assertEquals(300L, result.messageId(), "恰好 100 个元素应当放行（上限不得差一）");
    }

    @Test
    void shouldRejectFileIdsListExceedingElementLimit() throws Exception {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> dispatcher.dispatch(fileCardCommandWithFileIds(101)));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        verifyNoInteractions(imMessageService, fileCardMessageService, realtimePushService);
    }

    private ImCommandRequest textCommandWithMentions(int count) throws Exception {
        StringBuilder json = new StringBuilder("{\"content\":\"hello\",\"mentions\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(1000 + i);
        }
        json.append("]}");
        return new ImCommandRequest(7L, "Bearer token", "SEND_TEXT", "request-7", "client-limit", 100L,
                payload(json.toString()));
    }

    private ImCommandRequest fileCardCommandWithFileIds(int count) throws Exception {
        StringBuilder json = new StringBuilder("{\"fileIds\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(2000 + i);
        }
        json.append("]}");
        return new ImCommandRequest(7L, "Bearer token", "SEND_FILE_CARD", "request-8", "client-limit", 100L,
                payload(json.toString()));
    }

    private JsonNode payload(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private ImMessageVO message(Long messageId, Long conversationId, String messageType) {
        return new ImMessageVO(
                messageId,
                conversationId,
                7L,
                "sender",
                "发送人",
                "",
                messageType,
                "hello",
                List.of(),
                null,
                "client",
                "STORED",
                null,
                null,
                null,
                false,
                0,
                LocalDateTime.of(2026, 4, 28, 10, 0)
        );
    }
}
