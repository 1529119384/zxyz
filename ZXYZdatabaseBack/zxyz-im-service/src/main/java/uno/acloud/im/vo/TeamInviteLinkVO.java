package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
@AllArgsConstructor
public class TeamInviteLinkVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long teamId;
    private String token;
    private String joinUrl;
    private Integer maxUses;
    private Integer usedCount;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
}
