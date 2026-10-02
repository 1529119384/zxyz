package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.dto.InternalBatchSystemNotificationRequest;

import java.util.LinkedHashSet;
import java.util.List;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

@Service
public class InternalSystemNotificationService {

    private final SystemNotificationService systemNotificationService;

    public InternalSystemNotificationService(SystemNotificationService systemNotificationService) {
        this.systemNotificationService = systemNotificationService;
    }

    public void batchNotify(InternalBatchSystemNotificationRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "request 不能为空");
        }
        List<Long> userIds = request.getUserIds() == null
                ? List.of()
                : request.getUserIds().stream()
                .filter(userId -> userId != null && userId > 0)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
        if (userIds.isEmpty()) {
            return;
        }
        String type = requireText(request.getType(), "type 不能为空");
        String title = requireText(request.getTitle(), "title 不能为空");
        String content = requireText(request.getContent(), "content 不能为空");
        String normalizedBusinessType = optionalText(request.getBusinessType());
        String businessType = normalizedBusinessType == null ? type : normalizedBusinessType;
        // 走批量插入版本（B-22）：原先循环逐用户调 createNotification，
        // 每用户 2 条 SQL（insert + appendNotification 的会话写入）；
        // team-service 广播按 500/批调用本端点 ⇒ 1000 次 SQL/批。
        // 批量版本把通知行收敛为单条 batchInsert（appendNotification 仍需逐条，语义如此）。
        systemNotificationService.batchCreateNotifications(
                userIds,
                type,
                title,
                content,
                businessType,
                request.getBusinessId(),
                request.getTeamId()
        );
    }
}
