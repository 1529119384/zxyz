package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.SystemNotificationStatus;
import uno.acloud.im.infrastructure.persistence.entity.SystemNotification;
import uno.acloud.im.infrastructure.mapper.SystemNotificationMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageMapper;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import java.util.ArrayList;
import uno.acloud.im.domain.enums.MessageType;
import uno.acloud.im.infrastructure.persistence.entity.ImMessage;
import uno.acloud.im.vo.ImMessageVO;
import uno.acloud.im.vo.SystemNotificationVO;
import uno.acloud.im.vo.UnreadCountVO;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

@Service
public class SystemNotificationService {

    /** 66 号二.7①：广播消息批量落库的单批上限 */
    static final int MESSAGE_INSERT_BATCH = 500;

    private final SystemNotificationMapper systemNotificationMapper;
    private final SystemConversationService systemConversationService;
    private final ImMessageMapper imMessageMapper;
    private final ConversationMapper conversationMapper;
    private final ImRealtimePushService realtimePushService;

    public SystemNotificationService(SystemNotificationMapper systemNotificationMapper,
                                     SystemConversationService systemConversationService,
                                     ImMessageMapper imMessageMapper,
                                     ConversationMapper conversationMapper,
                                     ImRealtimePushService realtimePushService) {
        this.systemNotificationMapper = systemNotificationMapper;
        this.systemConversationService = systemConversationService;
        this.imMessageMapper = imMessageMapper;
        this.conversationMapper = conversationMapper;
        this.realtimePushService = realtimePushService;
    }

    public List<SystemNotificationVO> listNotifications(Long userId, Long teamId, Integer page, Integer pageSize) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        int offset = (safePage - 1) * safePageSize;
        return systemNotificationMapper.listByUser(userId, teamId, offset, safePageSize);
    }

    public List<SystemNotificationVO> listNotifications(Long userId, Integer page, Integer pageSize) {
        return listNotifications(userId, null, page, pageSize);
    }

    public UnreadCountVO getUnreadCount(Long userId, Long teamId) {
        return new UnreadCountVO(systemNotificationMapper.countUnread(userId, teamId));
    }

    public UnreadCountVO getUnreadCount(Long userId) {
        return getUnreadCount(userId, null);
    }

    public void createNotification(Long userId,
                                   String type,
                                   String title,
                                   String content,
                                   String businessType,
                                   Long businessId) {
        createNotification(userId, type, title, content, businessType, businessId, null);
    }

    public void createNotification(Long userId,
                                   String type,
                                   String title,
                                   String content,
                                   String businessType,
                                   Long businessId,
                                   Long teamId) {
        SystemNotification notification = new SystemNotification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setBusinessType(businessType);
        notification.setBusinessId(businessId);
        notification.setTeamId(teamId);
        notification.setStatus(SystemNotificationStatus.UNREAD);
        notification.setCreateTime(LocalDateTime.now());
        systemNotificationMapper.insertNotification(notification);
        systemConversationService.appendNotification(userId, title, content, type, businessId);
    }

    public void batchCreateNotifications(List<Long> userIds,
                                         String type,
                                         String title,
                                         String content,
                                         String businessType,
                                         Long businessId,
                                         Long teamId) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<SystemNotification> notifications = userIds.stream()
                .map(uid -> {
                    SystemNotification n = new SystemNotification();
                    n.setUserId(uid);
                    n.setType(type);
                    n.setTitle(title);
                    n.setContent(content);
                    n.setBusinessType(businessType);
                    n.setBusinessId(businessId);
                    n.setTeamId(teamId);
                    n.setStatus(SystemNotificationStatus.UNREAD);
                    n.setCreateTime(now);
                    return n;
                })
                .toList();
        systemNotificationMapper.batchInsert(notifications);
        // 66 号二.7①：会话 ensure 逐用户（幂等 upsert，无法安全批量化）保留，
        // 消息落库改批量 batchInsert —— 广播 N=1000 时 insert 从 N 条降到 ceil(N/500) 条。
        List<Long> conversationIds = userIds.stream()
                .map(systemConversationService::ensureSystemConversation)
                .toList();
        List<ImMessage> messages = new java.util.ArrayList<>(userIds.size());
        for (int i = 0; i < userIds.size(); i++) {
            ImMessage message = new ImMessage();
            message.initializeNew(conversationIds.get(i), null, MessageType.SYSTEM_NOTIFICATION,
                    systemConversationService.writeSystemContent(title, content, type, businessId),
                    "system-" + UUID.randomUUID());
            messages.add(message);
        }
        for (int from = 0; from < messages.size(); from += MESSAGE_INSERT_BATCH) {
            imMessageMapper.batchInsert(
                    messages.subList(from, Math.min(from + MESSAGE_INSERT_BATCH, messages.size())));
        }
        for (Long conversationId : conversationIds) {
            conversationMapper.incrementUnreadForOthers(conversationId, 0L);
            conversationMapper.touchConversation(conversationId);
        }
        for (int i = 0; i < userIds.size(); i++) {
            realtimePushService.pushMessageReceived(List.of(userIds.get(i)),
                    toBroadcastMessageVO(conversationIds.get(i), title, content, type, businessId, now));
        }
    }

    private ImMessageVO toBroadcastMessageVO(Long conversationId, String title, String content,
                                             String type, Long businessId, LocalDateTime now) {
        return new ImMessageVO(
                0L,
                conversationId,
                null, null, null, null,
                type,
                systemConversationService.writeSystemContent(title, content, type, businessId),
                List.of(),
                null,
                "system-" + java.util.UUID.randomUUID(),
                "STORED",
                null, null, null,
                false, 0,
                now
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long notificationId) {
        if (notificationId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "notificationId 不能为空");
        }
        if (systemNotificationMapper.markRead(notificationId, userId) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_NOTIFICATION_NOT_FOUND, "系统消息不存在");
        }
    }

    public void markBusinessRead(Long userId, String businessType, Long businessId) {
        systemNotificationMapper.markBusinessRead(userId, businessType, businessId);
    }
}
