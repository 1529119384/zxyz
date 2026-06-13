package uno.acloud.im.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import uno.acloud.im.domain.event.ImDomainEvent;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class ImDomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public ImDomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public void publish(String type, Map<String, Object> payload) {
        applicationEventPublisher.publishEvent(new ImDomainEvent(type, LocalDateTime.now(), payload == null ? Map.of() : Map.copyOf(payload)));
    }
}
