package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.SystemNotificationStatus;
import uno.acloud.im.infrastructure.persistence.entity.SystemNotification;
import uno.acloud.im.infrastructure.mapper.SystemNotificationMapper;
import uno.acloud.im.vo.SystemNotificationVO;
import uno.acloud.im.vo.UnreadCountVO;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SystemNotificationService {

    private final SystemNotificationMapper systemNotificationMapper;
    private final SystemConversationService systemConversationService;

    public SystemNotificationService(SystemNotificationMapper systemNotificationMapper,
                                     SystemConversationService systemConversationService) {
        this.systemNotificationMapper = systemNotificationMapper;
        this.systemConversationService = systemConversationService;
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
        // Must also append to each user's system conversation (matches single createNotification behavior)
        for (Long userId : userIds) {
            systemConversationService.appendNotification(userId, title, content, type, businessId);
        }
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
