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
        for (Long userId : userIds) {
            systemNotificationService.createNotification(
                    userId,
                    type,
                    title,
                    content,
                    businessType,
                    request.getBusinessId(),
                    request.getTeamId()
            );
        }
    }
}
