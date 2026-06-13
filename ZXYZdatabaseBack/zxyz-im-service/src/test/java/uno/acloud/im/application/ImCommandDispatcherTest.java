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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals("不支持的消息类型: SEND_IMAGE", exception.getMessage());
        verifyNoInteractions(imMessageService, fileCardMessageService, realtimePushService);
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
