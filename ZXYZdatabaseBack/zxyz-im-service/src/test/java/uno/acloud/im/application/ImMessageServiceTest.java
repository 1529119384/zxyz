package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.model.ImConversation;
import uno.acloud.im.domain.model.ImMessage;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageViewRow;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.vo.ImMessageVO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImMessageServiceTest {

    @Mock
    private ConversationService conversationService;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private ImMessageMapper imMessageMapper;
    @Mock
    private ConversationSerialExecutor serialExecutor;
    @Mock
    private TeamMutePolicyService mutePolicyService;
    @Mock
    private TeamMapper teamMapper;
    @Mock
    private SystemNotificationService notificationService;
    @Mock
    private ImDomainEventPublisher domainEventPublisher;

    private ObjectMapper objectMapper;
    private ImMessageService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ImMessageService(
                conversationService, conversationMapper, imMessageMapper,
                objectMapper, serialExecutor, mutePolicyService,
                teamMapper, notificationService, domainEventPublisher);

        // By default, make the serial executor just run the supplier directly
        when(serialExecutor.executeMessageWrite(anyLong(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
    }

    // ---- helper methods ----

    private ImConversation teamConversation(Long id, Long teamId) {
        ImConversation conv = new ImConversation();
        conv.setId(id);
        conv.setTeamId(teamId);
        conv.setReadOnly(Boolean.FALSE);
        return conv;
    }

    private ImMessageViewRow buildViewRow(Long messageId, Long conversationId, Long senderUserId, String content, String rawContent) {
        ImMessageViewRow row = new ImMessageViewRow();
        row.setMessageId(messageId);
        row.setConversationId(conversationId);
        row.setSenderUserId(senderUserId);
        row.setSenderUsername("user1");
        row.setSenderName("User One");
        row.setSenderAvatar(null);
        row.setMessageType("TEXT");
        row.setContent(content);
        row.setRawContent(rawContent);
        row.setClientMessageId("cid-001");
        row.setStatus(0);
        row.setReadByPeer(false);
        row.setReadCount(0);
        row.setCreateTime(LocalDateTime.now());
        return row;
    }

    // ---- storeTextMessage tests ----

    @Test
    void storeTextMessage_shouldSucceed_whenConversationExistsAndNotReadOnly() {
        Long conversationId = 10L;
        Long senderUserId = 100L;

        ImConversation conversation = teamConversation(conversationId, 5L);
        when(conversationMapper.getConversationById(conversationId)).thenReturn(conversation);
        when(teamMapper.listActiveMemberUserIds(5L)).thenReturn(List.of(100L, 200L));

        // The actual insert + message VO fetch happens inside doStoreTextMessage
        doAnswer(invocation -> {
            ImMessage msg = invocation.getArgument(0);
            msg.setId(999L);
            return 1;
        }).when(imMessageMapper).insert(any(ImMessage.class));

        when(conversationMapper.listActiveMemberUserIds(conversationId)).thenReturn(List.of(100L, 200L));

        ImMessageViewRow viewRow = buildViewRow(999L, conversationId, senderUserId, "hello", "{\"content\":\"hello\",\"mentions\":[]}");
        when(imMessageMapper.getMessageRowById(999L)).thenReturn(viewRow);

        ImMessageService.StoreMessageResult result = service.storeTextMessage(
                senderUserId, conversationId, "cid-001", "hello");

        assertNotNull(result);
        assertEquals(999L, result.messageId());
        assertNotNull(result.message());
        assertEquals(List.of(100L, 200L), result.memberUserIds());

        verify(conversationService).requireConversationMember(conversationId, senderUserId);
        verify(mutePolicyService).requireCanSend(senderUserId, conversationId);
        verify(imMessageMapper).insert(any(ImMessage.class));
        verify(conversationMapper).incrementUnreadForOthers(conversationId, senderUserId);
        verify(conversationMapper).touchConversation(conversationId);
    }

    @Test
    void storeTextMessage_shouldValidateMentions_andRejectNonTeamMembers() {
        Long conversationId = 10L;
        Long senderUserId = 100L;
        Long nonMemberUserId = 999L;

        ImConversation conversation = teamConversation(conversationId, 5L);
        when(conversationMapper.getConversationById(conversationId)).thenReturn(conversation);
        // Team members: 100 and 200; 999 is NOT a member
        when(teamMapper.listActiveMemberUserIds(5L)).thenReturn(List.of(100L, 200L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.storeTextMessage(senderUserId, conversationId, "cid-001", "hello @someone",
                        List.of(nonMemberUserId)));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("@"));
        verify(imMessageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    void storeTextMessage_shouldReject_whenConversationIsReadOnly() {
        Long conversationId = 10L;
        Long senderUserId = 100L;

        doThrow(new BusinessException(ErrorCode.TEAM_PERMISSION_DENIED, "项目群聊已归档，只允许查看历史消息"))
                .when(conversationService).requireWritableConversation(conversationId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.storeTextMessage(senderUserId, conversationId, "cid-001", "hello"));

        assertEquals(ErrorCode.TEAM_PERMISSION_DENIED, ex.getErrorCode());
        verify(imMessageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    void storeTextMessage_shouldReject_whenConversationDoesNotExist() {
        Long conversationId = 999L;
        Long senderUserId = 100L;

        // requireConversationMember throws when conversation is not found
        doThrow(new BusinessException(ErrorCode.NO_PERMISSION, "你无权访问该会话"))
                .when(conversationService).requireConversationMember(conversationId, senderUserId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.storeTextMessage(senderUserId, conversationId, "cid-001", "hello"));

        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
        verify(imMessageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    void storeTextMessage_shouldRejectBlankContent() {
        Long conversationId = 10L;
        Long senderUserId = 100L;

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.storeTextMessage(senderUserId, conversationId, "cid-001", "   "));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("消息内容不能为空"));
    }

    @Test
    void storeTextMessage_shouldRejectEmptyClientMessageId() {
        Long conversationId = 10L;
        Long senderUserId = 100L;

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.storeTextMessage(senderUserId, conversationId, "", "hello"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("clientMessageId"));
    }

    // ---- normalizeLimit tests ----

    @Test
    void normalizeLimit_shouldReturnDefaultWhenNull() {
        assertEquals(50, service.normalizeLimit(null));
    }

    @Test
    void normalizeLimit_shouldReturnDefaultWhenZero() {
        assertEquals(50, service.normalizeLimit(0));
    }

    @Test
    void normalizeLimit_shouldReturnDefaultWhenNegative() {
        assertEquals(50, service.normalizeLimit(-1));
    }

    @Test
    void normalizeLimit_shouldCapAt100() {
        assertEquals(100, service.normalizeLimit(200));
    }

    @Test
    void normalizeLimit_shouldReturnValueWhenValid() {
        assertEquals(30, service.normalizeLimit(30));
    }

    // ---- normalizeClientMessageId tests ----

    @Test
    void normalizeClientMessageId_shouldRejectBlank() {
        assertThrows(BusinessException.class, () -> service.normalizeClientMessageId(""));
        assertThrows(BusinessException.class, () -> service.normalizeClientMessageId(null));
        assertThrows(BusinessException.class, () -> service.normalizeClientMessageId("  "));
    }

    @Test
    void normalizeClientMessageId_shouldRejectTooLong() {
        String longId = "a".repeat(65);
        assertThrows(BusinessException.class, () -> service.normalizeClientMessageId(longId));
    }

    @Test
    void normalizeClientMessageId_shouldAcceptValidId() {
        assertEquals("abc-123", service.normalizeClientMessageId("abc-123"));
    }

    // ---- searchMessages tests ----

    @Test
    void searchMessages_shouldReturnEmptyForShortKeyword() {
        List<ImMessageVO> result = service.searchMessages(100L, 10L, "a", 10);
        assertTrue(result.isEmpty());
        verifyNoInteractions(imMessageMapper);
    }

    @Test
    void searchMessages_shouldReturnEmptyForNullKeyword() {
        List<ImMessageVO> result = service.searchMessages(100L, 10L, null, 10);
        assertTrue(result.isEmpty());
        verifyNoInteractions(imMessageMapper);
    }
}
