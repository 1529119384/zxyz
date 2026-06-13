package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.MessageType;
import uno.acloud.im.domain.model.ImMessage;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.vo.ImMessageVO;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TeamNotificationConversationService {

    private final ConversationMapper conversationMapper;
    private final TeamMapper teamMapper;
    private final ImMessageMapper imMessageMapper;
    private final ImMessageService imMessageService;
    private final ObjectMapper objectMapper;
    private final ImRealtimePushService realtimePushService;

    public TeamNotificationConversationService(ConversationMapper conversationMapper,
                                               TeamMapper teamMapper,
                                               ImMessageMapper imMessageMapper,
                                               ImMessageService imMessageService,
                                               ObjectMapper objectMapper,
                                               ImRealtimePushService realtimePushService) {
        this.conversationMapper = conversationMapper;
        this.teamMapper = teamMapper;
        this.imMessageMapper = imMessageMapper;
        this.imMessageService = imMessageService;
        this.objectMapper = objectMapper;
        this.realtimePushService = realtimePushService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long ensureConversation(Long teamId) {
        if (teamId == null || teamId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "teamId 不能为空");
        }
        String bizKey = "TEAM_NOTIFICATION:" + teamId;
        conversationMapper.upsertTeamNotificationConversation(teamId, bizKey);
        Long conversationId = conversationMapper.getTeamNotificationConversationId(bizKey);
        if (conversationId == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "团队消息会话创建失败");
        }
        for (Long userId : teamMapper.listActiveMemberUserIds(teamId)) {
            conversationMapper.upsertConversationMember(conversationId, userId);
        }
        return conversationId;
    }

    @Transactional(rollbackFor = Exception.class)
    public ImMessageVO appendAnnouncement(Long teamId, Long operatorUserId, String title, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("content", content);
        payload.put("teamId", teamId);
        payload.put("operatorUserId", operatorUserId);
        return storeAndPush(ensureConversation(teamId), operatorUserId, MessageType.ANNOUNCEMENT, payload);
    }

    @Transactional(rollbackFor = Exception.class)
    public ImMessageVO appendProjectCreateRequest(Long teamId, Long senderUserId, Map<String, Object> payload) {
        return storeAndPush(ensureConversation(teamId), senderUserId, MessageType.PROJECT_CREATE_REQUEST, payload);
    }

    @Transactional(rollbackFor = Exception.class)
    public ImMessageVO updateProjectCreateRequestStatus(Long teamId,
                                                  Long applicationId,
                                                  Long reviewerUserId,
                                                  boolean approved,
                                                  Long projectId,
                                                  String reviewReason) {
        Long conversationId = ensureConversation(teamId);
        Long messageId = imMessageMapper.getLatestProjectCreateRequestMessageId(conversationId, applicationId);
        if (messageId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目申请消息不存在");
        }
        ImMessageVO current = imMessageService.getMessageVOById(messageId);
        Map<String, Object> payload = parsePayload(current.getContent());
        payload.put("status", approved ? "APPROVED" : "REJECTED");
        payload.put("approved", approved);
        payload.put("projectId", projectId);
        payload.put("reviewerUserId", reviewerUserId);
        payload.put("reviewReason", reviewReason);
        payload.put("reviewTime", LocalDateTime.now().toString());
        if (imMessageMapper.updateMessageContent(messageId, writeValue(payload)) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新项目申请消息失败");
        }
        ImMessageVO updated = imMessageService.getMessageVOById(messageId);
        // Netty push deferred to afterCommit to avoid holding DB connection during remote I/O
        List<Long> memberUserIds = conversationMapper.listActiveMemberUserIds(conversationId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                realtimePushService.pushMessageReceived(memberUserIds, updated);
            }
        });
        return updated;
    }

    private ImMessageVO storeAndPush(Long conversationId,
                                     Long senderUserId,
                                     String messageType,
                                     Map<String, Object> payload) {
        ImMessage message = new ImMessage();
        message.setConversationId(conversationId);
        message.setSenderUserId(senderUserId);
        message.setMessageType(messageType);
        message.setContent(writeValue(payload));
        message.setStatus(0);
        message.setClientMessageId("team-notify-" + UUID.randomUUID());
        message.setCreateTime(LocalDateTime.now());
        imMessageMapper.insert(message);
        conversationMapper.incrementUnreadForOthers(conversationId, senderUserId == null ? -1 : senderUserId);
        conversationMapper.touchConversation(conversationId);
        ImMessageVO messageVO = imMessageService.getMessageVOById(message.getId());
        // Netty push deferred to afterCommit to avoid holding DB connection during remote I/O
        List<Long> memberUserIds = conversationMapper.listActiveMemberUserIds(conversationId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                realtimePushService.pushMessageReceived(memberUserIds, messageVO);
            }
        });
        return messageVO;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String rawContent) {
        try {
            return objectMapper.readValue(rawContent, LinkedHashMap.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "团队消息内容损坏");
        }
    }

    private String writeValue(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "团队消息序列化失败");
        }
    }
}
