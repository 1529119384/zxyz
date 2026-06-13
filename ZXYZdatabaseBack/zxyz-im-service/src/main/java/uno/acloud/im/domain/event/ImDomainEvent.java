package uno.acloud.im.domain.event;

import java.time.LocalDateTime;
import java.util.Map;

public record ImDomainEvent(
        String type,
        LocalDateTime occurredAt,
        Map<String, Object> payload
) {
}
