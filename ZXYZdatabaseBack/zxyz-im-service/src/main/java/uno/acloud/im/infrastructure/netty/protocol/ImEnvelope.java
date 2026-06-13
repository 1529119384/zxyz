package uno.acloud.im.infrastructure.netty.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class ImEnvelope {
    private String type;
    private String requestId;
    private String clientMessageId;
    private Long conversationId;
    private JsonNode payload;
    private Long timestamp;
}
