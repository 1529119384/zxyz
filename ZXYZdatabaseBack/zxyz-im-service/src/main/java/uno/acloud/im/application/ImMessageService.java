package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.event.ImDomainEventType;
import uno.acloud.im.domain.enums.MessageType;
import uno.acloud.im.domain.enums.SystemNotificationType;
import uno.acloud.im.infrastructure.persistence.entity.FileCardContent;
import uno.acloud.im.infrastructure.persistence.entity.ImConversation;
import uno.acloud.im.infrastructure.persistence.entity.ImMessage;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageViewRow;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.vo.FileCardEntryVO;
import uno.acloud.im.vo.FileCardVO;
import uno.acloud.im.vo.ImMessageVO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ImMessageService {

    /** IM 消息最大文本长度 fallback */
    private static final int FALLBACK_MAX_TEXT_LENGTH = 5000;

    private final ConversationService conversationService;
    private final ConversationMapper conversationMapper;
    private final ImMessageMapper imMessageMapper;
    private final ObjectMapper objectMapper;
    private final ConversationSerialExecutor serialExecutor;
    private final TeamMutePolicyService mutePolicyService;
    private final TeamMapper teamMapper;
    private final SystemNotificationService notificationService;
    private final ImDomainEventPublisher domainEventPublisher;
    private final ConfigGetter configGetter;
    private final int maxTextLength;

    public ImMessageService(ConversationService conversationService,
                            ConversationMapper conversationMapper,
                            ImMessageMapper imMessageMapper,
                            ObjectMapper objectMapper,
                            ConversationSerialExecutor serialExecutor,
                            TeamMutePolicyService mutePolicyService,
                            TeamMapper teamMapper,
                            SystemNotificationService notificationService,
                            ImDomainEventPublisher domainEventPublisher,
                            ConfigGetter configGetter) {
        this.conversationService = conversationService;
        this.conversationMapper = conversationMapper;
        this.imMessageMapper = imMessageMapper;
        this.objectMapper = objectMapper;
        this.serialExecutor = serialExecutor;
        this.mutePolicyService = mutePolicyService;
        this.teamMapper = teamMapper;
        this.notificationService = notificationService;
        this.domainEventPublisher = domainEventPublisher;
        this.configGetter = configGetter;
        this.maxTextLength = configGetter.getInt("app.im.message.max-text-length", FALLBACK_MAX_TEXT_LENGTH);
    }

    @Transactional(rollbackFor = Exception.class)
    public StoreMessageResult storeTextMessage(Long senderUserId,
                                               Long conversationId,
                                               String clientMessageId,
                                               String content) {
        return storeTextMessage(senderUserId, conversationId, clientMessageId, content, List.of());
    }

    @Transactional(rollbackFor = Exception.class)
    public StoreMessageResult storeTextMessage(Long senderUserId,
                                               Long conversationId,
                                               String clientMessageId,
                                               String content,
                                               List<Long> mentions) {
        String normalizedClientMessageId = normalizeClientMessageId(clientMessageId);
        String normalizedContent = normalizeTextContent(content);
        conversationService.requireConversationMember(conversationId, senderUserId);
        conversationService.requireWritableConversation(conversationId);
        mutePolicyService.requireCanSend(senderUserId, conversationId);
        List<Long> normalizedMentions = normalizeMentions(senderUserId, conversationId, mentions);

        return serialExecutor.executeMessageWrite(conversationId, () -> {
            ImMessage existing = imMessageMapper.getByClientMessageId(conversationId, senderUserId, normalizedClientMessageId);
            if (existing != null) {
                ImMessageVO existingMessage = getMessageVOById(existing.getId());
                return new StoreMessageResult(existing.getId(), existingMessage, conversationMapper.listActiveMemberUserIds(conversationId));
            }
            return doStoreTextMessage(senderUserId, conversationId, normalizedClientMessageId, normalizedContent, normalizedMentions);
        });
    }

    public List<ImMessageVO> listMessages(Long userId,
                                          Long conversationId,
                                          Long afterMessageId,
                                          LocalDateTime afterTime,
                                          Long beforeMessageId,
                                          Integer limit) {
        conversationService.requireConversationMember(conversationId, userId);
        int normalizedLimit = normalizeLimit(limit);
        return imMessageMapper.listMessageRows(conversationId, userId, afterMessageId, afterTime, beforeMessageId, normalizedLimit)
                .stream()
                .map(this::toMessageVO)
                .toList();
    }

    public ImMessageVO getMessageVOById(Long messageId) {
        ImMessageViewRow row = imMessageMapper.getMessageRowById(messageId);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "消息不存在");
        }
        return toMessageVO(row);
    }

    public List<ImMessageVO> searchMessages(Long userId, Long conversationId, String keyword, Integer limit) {
        if (keyword == null || keyword.trim().length() < 2) {
            return List.of();
        }
        conversationService.requireConversationMember(conversationId, userId);
        String normalizedKeyword = keyword.trim();
        return imMessageMapper.searchMessageRows(conversationId, normalizedKeyword, normalizeLimit(limit))
                .stream()
                .map(this::toMessageVO)
                .toList();
    }

    protected StoreMessageResult doStoreTextMessage(Long senderUserId,
                                                    Long conversationId,
                                                    String clientMessageId,
                                                    String content) {
        return doStoreTextMessage(senderUserId, conversationId, clientMessageId, content, List.of());
    }

    protected StoreMessageResult doStoreTextMessage(Long senderUserId,
                                                    Long conversationId,
                                                    String clientMessageId,
                                                    String content,
                                                    List<Long> mentions) {
        ImMessage message = new ImMessage();
        message.initializeNew(conversationId, senderUserId, MessageType.TEXT, writeTextContent(content, mentions), clientMessageId);
        imMessageMapper.insert(message);

        conversationMapper.incrementUnreadForOthers(conversationId, senderUserId);
        conversationMapper.touchConversation(conversationId);

        ImMessageVO messageVO = getMessageVOById(message.getId());
        List<Long> memberUserIds = conversationMapper.listActiveMemberUserIds(conversationId);
        notifyMentionedUsers(senderUserId, message.getId(), content, mentions);
        return new StoreMessageResult(message.getId(), messageVO, memberUserIds);
    }

    public int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 50;
        }
        return Math.min(limit, 100);
    }

    public String normalizeClientMessageId(String clientMessageId) {
        String value = clientMessageId == null ? "" : clientMessageId.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "clientMessageId 不能为空");
        }
        if (value.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "clientMessageId 长度不能超过 64");
        }
        return value;
    }

    private String normalizeTextContent(String content) {
        String value = content == null ? "" : content.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "消息内容不能为空");
        }
        if (value.length() > maxTextLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "消息内容长度不能超过 " + maxTextLength);
        }
        return value;
    }

    private String writeTextContent(String content, List<Long> mentions) {
        try {
            return objectMapper.createObjectNode()
                    .put("content", content)
                    .putPOJO("mentions", mentions == null ? List.of() : mentions)
                    .toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "消息序列化失败");
        }
    }

    private ImMessageVO toMessageVO(ImMessageViewRow row) {
        boolean recalled = Integer.valueOf(1).equals(row.getStatus());
        FileCardVO fileCard = null;
        if (!recalled && MessageType.FILE_CARD.equals(row.getMessageType()) && row.getRawContent() != null) {
            fileCard = toFileCardVO(readFileCardContent(row.getRawContent()));
        }
        return new ImMessageVO(
                row.getMessageId(),
                row.getConversationId(),
                row.getSenderUserId(),
                row.getSenderUsername(),
                row.getSenderName(),
                row.getSenderAvatar(),
                row.getMessageType(),
                row.getContent(),
                readMentions(row.getRawContent()),
                fileCard,
                recalled ? null : row.getClientMessageId(),
                recalled ? "RECALLED" : "STORED",
                row.getRecallByUserId(),
                row.getRecallTime(),
                row.getRecallReason(),
                row.getReadByPeer(),
                row.getReadCount(),
                row.getCreateTime()
        );
    }

    private List<Long> normalizeMentions(Long senderUserId, Long conversationId, List<Long> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return List.of();
        }
        ImConversation conversation = conversationMapper.getConversationById(conversationId);
        if (conversation == null || conversation.getTeamId() == null) {
            return List.of();
        }
        Set<Long> uniqueMentions = new LinkedHashSet<>(mentions);
        uniqueMentions.remove(senderUserId);
        // 批量查询团队活跃成员，避免逐个 getActiveMember 的 N+1 问题
        Set<Long> activeMemberIds = new java.util.HashSet<>(teamMapper.listActiveMemberUserIds(conversation.getTeamId()));
        for (Long mentionedUserId : uniqueMentions) {
            if (mentionedUserId == null || !activeMemberIds.contains(mentionedUserId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "@ 成员必须属于当前团队");
            }
        }
        return uniqueMentions.stream().toList();
    }

    private void notifyMentionedUsers(Long senderUserId, Long messageId, String content, List<Long> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return;
        }
        String summary = content == null ? "" : content;
        if (summary.length() > 120) {
            summary = summary.substring(0, 120);
        }
        // Batch insert all notifications in a single SQL statement
        notificationService.batchCreateNotifications(
                mentions,
                SystemNotificationType.TEAM_MENTION,
                "你被 @ 了",
                summary,
                SystemNotificationType.TEAM_MENTION,
                messageId,
                null
        );
        // Publish domain events individually (lightweight, no DB writes)
        for (Long mentionedUserId : mentions) {
            domainEventPublisher.publish(ImDomainEventType.TEAM_MENTION_CREATED, java.util.Map.of(
                    "messageId", messageId,
                    "senderUserId", senderUserId,
                    "mentionedUserId", mentionedUserId
            ));
        }
    }

    private List<Long> readMentions(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return List.of();
        }
        try {
            var node = objectMapper.readTree(rawContent).path("mentions");
            if (!node.isArray()) {
                return List.of();
            }
            java.util.ArrayList<Long> result = new java.util.ArrayList<>();
            node.forEach(item -> {
                if (item.canConvertToLong()) {
                    result.add(item.asLong());
                }
            });
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private FileCardContent readFileCardContent(String rawContent) {
        return FileCardContentUtils.parse(objectMapper, rawContent);
    }

    private FileCardVO toFileCardVO(FileCardContent content) {
        List<FileCardEntryVO> entries = content.getEntries() == null
                ? List.of()
                : content.getEntries().stream().map(FileCardEntryVO::fromSnapshot).toList();
        return new FileCardVO(
                content.getShareType(),
                content.getOwnerUserId(),
                content.getParentId(),
                content.getEntryCount(),
                entries
        );
    }

    public record StoreMessageResult(Long messageId, ImMessageVO message, List<Long> memberUserIds) {
    }
}
