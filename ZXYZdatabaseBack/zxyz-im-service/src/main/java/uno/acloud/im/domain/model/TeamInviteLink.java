package uno.acloud.im.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@TableName("team_invite_link")
public class TeamInviteLink implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private String token;
    private Long createdByUserId;
    private LocalDateTime expireTime;
    private Integer maxUses;
    private Integer usedCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
