package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.io.Serializable;
import java.util.List;

@Getter @Setter @ToString
@AllArgsConstructor
public class ImMessageVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long messageId;
    private Long conversationId;
    private Long senderUserId;
    private String senderUsername;
    private String senderName;
    private String senderAvatar;
    private String messageType;
    private String content;
    private List<Long> mentions;
    private FileCardVO fileCard;
    private String clientMessageId;
    private String status;
    private Long recallByUserId;
    private LocalDateTime recallTime;
    private String recallReason;
    private Boolean readByPeer;
    private Integer readCount;
    private LocalDateTime createTime;
}
