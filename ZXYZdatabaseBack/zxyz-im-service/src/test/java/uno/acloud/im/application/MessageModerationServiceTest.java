package uno.acloud.im.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import static uno.acloud.common.TeamErrorCode.*;
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.ConversationType;
import uno.acloud.im.infrastructure.persistence.entity.ImConversation;
import uno.acloud.im.infrastructure.persistence.entity.ImMessage;
import uno.acloud.im.infrastructure.persistence.entity.TeamMember;
import uno.acloud.im.dto.RecallMessageRequest;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageModerationServiceTest {

    @Mock
    private ImMessageMapper imMessageMapper;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private TeamMapper teamMapper;
    @Mock
    private ConversationService conversationService;
    @Mock
    private TeamPermissionService teamPermissionService;
    @Mock
    private ConfigGetter configGetter;

    private MessageModerationService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(configGetter.getInt("app.im.message.recall-window-seconds", 120)).thenReturn(120);
        service = new MessageModerationService(
                imMessageMapper, conversationMapper, teamMapper,
                conversationService, teamPermissionService, configGetter);
    }

    // ---- 辅助方法 ----

    private ImMessage buildMessage(Long id, Long conversationId, Long senderUserId, int status, LocalDateTime createTime) {
        ImMessage message = new ImMessage();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setSenderUserId(senderUserId);
        message.setStatus(status);
        message.setCreateTime(createTime);
        return message;
    }

    private ImConversation buildConversation(Long id, String type, Long teamId) {
        ImConversation conversation = new ImConversation();
        conversation.setId(id);
        conversation.setType(type);
        conversation.setTeamId(teamId);
        return conversation;
    }

    // ---- recall: 参数校验 ----

    @Test
    void recall_shouldThrowWhenMessageIdIsNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(100L, null, null));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verifyNoInteractions(imMessageMapper);
    }

    @Test
    void recall_shouldThrowWhenMessageIdIsZero() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(100L, 0L, null));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verifyNoInteractions(imMessageMapper);
    }

    @Test
    void recall_shouldThrowWhenMessageIdIsNegative() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(100L, -5L, null));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verifyNoInteractions(imMessageMapper);
    }

    // ---- recall: 消息不存在或已撤回 ----

    @Test
    void recall_shouldThrowWhenMessageNotFound() {
        when(imMessageMapper.getById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(100L, 999L, null));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不存在或已撤回"));
    }

    @Test
    void recall_shouldThrowWhenMessageAlreadyRecalled() {
        // status=1 表示已撤回
        ImMessage recalledMessage = buildMessage(1L, 10L, 100L, 1, LocalDateTime.now());
        when(imMessageMapper.getById(1L)).thenReturn(recalledMessage);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(100L, 1L, null));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不存在或已撤回"));
    }

    // ---- recall: 会话不存在 ----

    @Test
    void recall_shouldThrowWhenConversationNotFound() {
        ImMessage message = buildMessage(1L, 10L, 100L, 0, LocalDateTime.now().minusSeconds(30));
        when(imMessageMapper.getById(1L)).thenReturn(message);
        when(conversationMapper.getConversationById(10L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(100L, 1L, null));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("会话不存在"));
        verify(conversationService).requireConversationMember(10L, 100L);
    }

    // ---- recall: 权限校验 ----

    @Test
    void recall_shouldThrowWhenNotMember() {
        ImMessage message = buildMessage(1L, 10L, 100L, 0, LocalDateTime.now());
        when(imMessageMapper.getById(1L)).thenReturn(message);
        doThrow(new BusinessException(ErrorCode.NO_PERMISSION, "你无权访问该会话"))
                .when(conversationService).requireConversationMember(10L, 200L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(200L, 1L, null));

        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
        verify(imMessageMapper, never()).recallMessage(anyLong(), anyLong(), any());
    }

    @Test
    void recall_shouldThrowWhenRecallingOthersMessageWithoutPermission() {
        // 消息发送者是 100，操作者是 200（非发送者）
        ImMessage message = buildMessage(1L, 10L, 100L, 0, LocalDateTime.now());
        when(imMessageMapper.getById(1L)).thenReturn(message);

        // requireConversationMember 需要放行（200 是会话成员）
        doNothing().when(conversationService).requireConversationMember(10L, 200L);

        // 私聊会话，无团队，canManagerRecall 返回 false
        ImConversation conversation = buildConversation(10L, ConversationType.DIRECT, null);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(200L, 1L, null));

        assertEquals(TEAM_PERMISSION_DENIED.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不能撤回他人消息"));
    }

    // ---- recall: 撤回时间窗口 ----

    @Test
    void recall_shouldThrowWhenRecallWindowExpired() {
        // 消息创建于 5 分钟前，超过 120 秒窗口
        ImMessage message = buildMessage(1L, 10L, 100L, 0, LocalDateTime.now().minusMinutes(5));
        when(imMessageMapper.getById(1L)).thenReturn(message);

        // 私聊会话，操作者是发送者本人，但已超时
        ImConversation conversation = buildConversation(10L, ConversationType.DIRECT, null);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(100L, 1L, null));

        assertEquals(TEAM_PERMISSION_DENIED.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("超过可撤回时间"));
    }

    @Test
    void recall_shouldThrowWhenCreateTimeIsNull() {
        ImMessage message = buildMessage(1L, 10L, 100L, 0, null);
        when(imMessageMapper.getById(1L)).thenReturn(message);

        ImConversation conversation = buildConversation(10L, ConversationType.DIRECT, null);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(100L, 1L, null));

        assertEquals(TEAM_PERMISSION_DENIED.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("超过可撤回时间"));
    }

    // ---- recall: 并发操作 ----

    @Test
    void recall_shouldThrowWhenConcurrentModification() {
        ImMessage message = buildMessage(1L, 10L, 100L, 0, LocalDateTime.now().minusSeconds(30));
        when(imMessageMapper.getById(1L)).thenReturn(message);

        ImConversation conversation = buildConversation(10L, ConversationType.DIRECT, null);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);

        // recallMessage 返回 0，表示更新失败（状态已被其他请求变更）
        when(imMessageMapper.recallMessage(1L, 100L, null)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(100L, 1L, null));

        assertEquals(ErrorCode.CONCURRENT_OPERATION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("状态已变化"));
    }

    // ---- recall: 成功撤回自己的消息 ----

    @Test
    void recall_shouldSucceedWhenRecallingOwnMessageWithinWindow() {
        ImMessage message = buildMessage(1L, 10L, 100L, 0, LocalDateTime.now().minusSeconds(30));

        // 撤回后重新查询
        ImMessage recalledMessage = buildMessage(1L, 10L, 100L, 1, LocalDateTime.now().minusSeconds(30));
        recalledMessage.setRecallTime(LocalDateTime.now());
        // getById 被调用两次：第一次查原始消息，第二次查撤回后的消息
        when(imMessageMapper.getById(1L)).thenReturn(message).thenReturn(recalledMessage);

        ImConversation conversation = buildConversation(10L, ConversationType.DIRECT, null);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);
        when(imMessageMapper.recallMessage(1L, 100L, null)).thenReturn(1);
        when(conversationMapper.listActiveMemberUserIds(10L)).thenReturn(List.of(100L, 200L));

        MessageModerationService.RecallResult result = service.recall(100L, 1L, null);

        assertNotNull(result);
        assertNotNull(result.recall());
        assertEquals(1L, result.recall().getMessageId());
        assertEquals(10L, result.recall().getConversationId());
        assertEquals(100L, result.recall().getRecallByUserId());
        assertEquals(List.of(100L, 200L), result.memberUserIds());
        verify(imMessageMapper).recallMessage(1L, 100L, null);
    }

    @Test
    void recall_shouldPassReasonWhenProvided() {
        ImMessage message = buildMessage(1L, 10L, 100L, 0, LocalDateTime.now().minusSeconds(10));

        ImMessage recalledMessage = buildMessage(1L, 10L, 100L, 1, LocalDateTime.now().minusSeconds(10));
        recalledMessage.setRecallTime(LocalDateTime.now());
        when(imMessageMapper.getById(1L)).thenReturn(message).thenReturn(recalledMessage);

        ImConversation conversation = buildConversation(10L, ConversationType.DIRECT, null);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);

        RecallMessageRequest request = new RecallMessageRequest();
        request.setReason("发错了");
        when(imMessageMapper.recallMessage(1L, 100L, "发错了")).thenReturn(1);
        when(conversationMapper.listActiveMemberUserIds(10L)).thenReturn(List.of(100L));

        MessageModerationService.RecallResult result = service.recall(100L, 1L, request);

        assertNotNull(result);
        assertEquals("发错了", result.recall().getRecallReason());
        verify(imMessageMapper).recallMessage(1L, 100L, "发错了");
    }

    // ---- recall: 管理员撤回 ----

    @Test
    void recall_shouldAllowManagerToRecallOthersMessage() {
        // 消息发送者是 100，操作者是 200（团队管理员）
        ImMessage message = buildMessage(1L, 10L, 100L, 0, LocalDateTime.now().minusMinutes(10));

        ImMessage recalledMessage = buildMessage(1L, 10L, 100L, 1, LocalDateTime.now().minusMinutes(10));
        recalledMessage.setRecallTime(LocalDateTime.now());
        when(imMessageMapper.getById(1L)).thenReturn(message).thenReturn(recalledMessage);

        // 团队会话
        ImConversation conversation = buildConversation(10L, ConversationType.TEAM, 5L);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);

        // 管理员有 mute:manage 权限
        TeamMember managerMember = new TeamMember();
        managerMember.setTeamId(5L);
        managerMember.setUserId(200L);
        when(teamMapper.getActiveMember(5L, 200L)).thenReturn(managerMember);
        when(teamPermissionService.hasPermission(5L, 200L, "team:mute:manage")).thenReturn(true);

        when(imMessageMapper.recallMessage(1L, 200L, null)).thenReturn(1);
        when(conversationMapper.listActiveMemberUserIds(10L)).thenReturn(List.of(100L, 200L));

        MessageModerationService.RecallResult result = service.recall(200L, 1L, null);

        assertNotNull(result);
        assertEquals(200L, result.recall().getRecallByUserId());
        verify(teamPermissionService).hasPermission(5L, 200L, "team:mute:manage");
    }

    @Test
    void recall_shouldNotAllowNonManagerToRecallOthersMessageInTeam() {
        // 消息发送者是 100，操作者是 200（普通成员）
        ImMessage message = buildMessage(1L, 10L, 100L, 0, LocalDateTime.now().minusMinutes(10));
        when(imMessageMapper.getById(1L)).thenReturn(message);

        // requireConversationMember 需要放行（200 是会话成员）
        doNothing().when(conversationService).requireConversationMember(10L, 200L);

        ImConversation conversation = buildConversation(10L, ConversationType.TEAM, 5L);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);

        // 200 是团队成员但没有 mute:manage 权限
        TeamMember member = new TeamMember();
        member.setTeamId(5L);
        member.setUserId(200L);
        when(teamMapper.getActiveMember(5L, 200L)).thenReturn(member);
        when(teamPermissionService.hasPermission(5L, 200L, "team:mute:manage")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recall(200L, 1L, null));

        assertEquals(TEAM_PERMISSION_DENIED.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不能撤回他人消息"));
    }
}
