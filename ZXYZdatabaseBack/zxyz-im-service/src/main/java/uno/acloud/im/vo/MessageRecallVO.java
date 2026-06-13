package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
@AllArgsConstructor
public class MessageRecallVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long messageId;
    private Long conversationId;
    private Long recallByUserId;
    private LocalDateTime recallTime;
    private String recallReason;
}
