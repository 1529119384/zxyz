package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.MessageType;
import uno.acloud.im.domain.event.ImDomainEventType;
import uno.acloud.im.domain.model.ImMessage;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageMapper;
import uno.acloud.im.dto.PublishAnnouncementRequest;
import uno.acloud.im.vo.ImMessageVO;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static uno.acloud.common.InputNormalizer.requireText;

@Service
public class AnnouncementService {

    private final ConversationMapper conversationMapper;
    private final ImMessageMapper imMessageMapper;
    private final ImMessageService imMessageService;
    private final TeamNotificationConversationService teamNotificationConversationService;
    private final TeamPermissionService teamPermissionService;
    private final ImDomainEventPublisher domainEventPublisher;
    private final ObjectMapper objectMapper;

    public AnnouncementService(ConversationMapper conversationMapper,
                               ImMessageMapper imMessageMapper,
                               ImMessageService imMessageService,
                               TeamNotificationConversationService teamNotificationConversationService,
                               TeamPermissionService teamPermissionService,
                               ImDomainEventPublisher domainEventPublisher,
                               ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.imMessageMapper = imMessageMapper;
        this.imMessageService = imMessageService;
        this.teamNotificationConversationService = teamNotificationConversationService;
        this.teamPermissionService = teamPermissionService;
        this.domainEventPublisher = domainEventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public ImMessageService.StoreMessageResult publishAnnouncement(Long operatorUserId,
                                                                   Long teamId,
                                                                   PublishAnnouncementRequest request) {
        teamPermissionService.requirePermission(teamId, operatorUserId, TeamPermissionCodes.TEAM_ANNOUNCEMENT_PUBLISH);
        Long conversationId = conversationMapper.getTeamConversationId(teamId);
        if (conversationId == null) {
            throw new BusinessException(ErrorCode.TEAM_NOT_FOUND, "团队会话不存在");
        }
        String title = requireText(request == null ? null : request.getTitle(), "公告标题不能为空", 120, "内容长度不能超过 120");
        String content = requireText(request == null ? null : request.getContent(), "公告内容不能为空", 5000, "内容长度不能超过 5000");

        ImMessage message = new ImMessage();
        message.setConversationId(conversationId);
        message.setSenderUserId(operatorUserId);
        message.setMessageType(MessageType.ANNOUNCEMENT);
        message.setContent(writeAnnouncementContent(title, content));
        message.setStatus(0);
        message.setClientMessageId("announcement-" + UUID.randomUUID());
        message.setCreateTime(LocalDateTime.now());
        imMessageMapper.insert(message);
        conversationMapper.incrementUnreadForOthers(conversationId, operatorUserId);
        conversationMapper.touchConversation(conversationId);

        teamNotificationConversationService.appendAnnouncement(teamId, operatorUserId, title, content);
        // MQ publish deferred to afterCommit to avoid holding DB connection during remote I/O
        Long messageId = message.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                domainEventPublisher.publish(ImDomainEventType.TEAM_ANNOUNCEMENT_PUBLISHED, Map.of(
                        "teamId", teamId,
                        "operatorUserId", operatorUserId,
                        "messageId", messageId,
                        "title", title
                ));
            }
        });
        ImMessageVO messageVO = imMessageService.getMessageVOById(message.getId());
        return new ImMessageService.StoreMessageResult(message.getId(), messageVO, conversationMapper.listActiveMemberUserIds(conversationId));
    }

    private String writeAnnouncementContent(String title, String content) {
        try {
            return objectMapper.createObjectNode()
                    .put("title", title)
                    .put("content", content)
                    .toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "公告内容序列化失败");
        }
    }
}
