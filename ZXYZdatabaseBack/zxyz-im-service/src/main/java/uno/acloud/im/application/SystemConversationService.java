package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.MessageType;
import uno.acloud.im.infrastructure.persistence.entity.ImMessage;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageMapper;
import uno.acloud.im.vo.ImMessageVO;

import java.util.List;
import java.util.UUID;

@Service
public class SystemConversationService {

    private final ConversationMapper conversationMapper;
    private final ImMessageMapper imMessageMapper;
    private final ObjectMapper objectMapper;
    private final ImRealtimePushService realtimePushService;

    public SystemConversationService(ConversationMapper conversationMapper,
                                     ImMessageMapper imMessageMapper,
                                     ObjectMapper objectMapper,
                                     ImRealtimePushService realtimePushService) {
        this.conversationMapper = conversationMapper;
        this.imMessageMapper = imMessageMapper;
        this.objectMapper = objectMapper;
        this.realtimePushService = realtimePushService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long ensureSystemConversation(Long userId) {
        String bizKey = systemBizKey(userId);
        conversationMapper.upsertSystemConversation(bizKey);
        Long conversationId = conversationMapper.getSystemConversationId(bizKey);
        if (conversationId == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "System conversation create failed");
        }
        conversationMapper.upsertConversationMember(conversationId, userId);
        return conversationId;
    }

    @Transactional(rollbackFor = Exception.class)
    public void appendNotification(Long userId, String title, String content, String type, Long businessId) {
        Long conversationId = ensureSystemConversation(userId);
        ImMessage message = new ImMessage();
        message.initializeNew(conversationId, null, MessageType.SYSTEM_NOTIFICATION, writeSystemContent(title, content, type, businessId), "system-" + UUID.randomUUID());
        imMessageMapper.insert(message);
        conversationMapper.incrementUnreadForOthers(conversationId, 0L);
        conversationMapper.touchConversation(conversationId);
        realtimePushService.pushMessageReceived(List.of(userId), toSystemMessageVO(message));
    }

    private ImMessageVO toSystemMessageVO(ImMessage message) {
        // 实时推送与历史查询保持同一消息体，前端统一解析标题和正文。
        return new ImMessageVO(
                message.getId(),
                message.getConversationId(),
                null,
                null,
                null,
                null,
                message.getMessageType(),
                message.getContent(),
                List.of(),
                null,
                message.getClientMessageId(),
                "STORED",
                null,
                null,
                null,
                false,
                0,
                message.getCreateTime()
        );
    }

    private String writeSystemContent(String title, String content, String type, Long businessId) {
        try {
            return objectMapper.createObjectNode()
                    .put("title", title == null ? "System message" : title)
                    .put("content", content == null ? "" : content)
                    .put("type", type == null ? "" : type)
                    .put("businessId", businessId == null ? 0L : businessId)
                    .toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "System message serialize failed");
        }
    }

    private String systemBizKey(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId is required");
        }
        return "SYSTEM:" + userId;
    }
}
