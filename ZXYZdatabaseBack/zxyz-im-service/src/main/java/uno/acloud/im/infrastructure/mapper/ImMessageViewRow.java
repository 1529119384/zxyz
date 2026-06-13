package uno.acloud.im.infrastructure.mapper;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@Setter
@ToString
public class ImMessageViewRow {
    private Long messageId;
    private Long conversationId;
    private Long senderUserId;
    private String senderUsername;
    private String senderName;
    private String senderAvatar;
    private String messageType;
    private String content;
    private String rawContent;
    private String clientMessageId;
    private Integer status;
    private Long recallByUserId;
    private LocalDateTime recallTime;
    private String recallReason;
    private Boolean readByPeer;
    private Integer readCount;
    private LocalDateTime createTime;
}
