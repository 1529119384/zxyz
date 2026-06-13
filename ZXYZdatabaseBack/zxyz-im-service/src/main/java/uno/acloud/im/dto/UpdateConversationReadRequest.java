package uno.acloud.im.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
public class UpdateConversationReadRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "最后已读消息ID不能为空")
    private Long lastReadMessageId;
}
