package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
@AllArgsConstructor
public class ConversationReadVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long conversationId;
    private Long readerUserId;
    private Long lastReadMessageId;
}
