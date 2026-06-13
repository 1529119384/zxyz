package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
@AllArgsConstructor
public class ConversationSummaryVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String type;
    private Long teamId;
    private Long projectId;
    private String name;
    private String avatar;
    private Integer unreadCount;
    private Long peerUserId;
    private String peerUsername;
    private String peerName;
    private String peerAvatar;
    private LocalDateTime updateTime;
}
