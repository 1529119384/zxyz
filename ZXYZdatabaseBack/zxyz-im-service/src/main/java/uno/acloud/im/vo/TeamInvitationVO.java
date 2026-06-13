package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
@AllArgsConstructor
public class TeamInvitationVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long teamId;
    private Long inviteeUserId;
    private Long inviterUserId;
    private Integer status;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
}
