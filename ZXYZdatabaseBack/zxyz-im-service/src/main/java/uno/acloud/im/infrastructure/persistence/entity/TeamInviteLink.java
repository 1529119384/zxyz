package uno.acloud.im.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
// B-13 / 60 号 🟡-4：@JsonProperty(WRITE_ONLY) 只管 JSON 序列化、不管 toString()——
// token 为 32 hex 邀请凭证，任何 log 实体路径都不得明文留存，与 email 侧 EmailServerConfig 同口径。
@ToString(exclude = {"token"})
@TableName("team_invite_link")
public class TeamInviteLink implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long teamId;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String token;
    private Long createdByUserId;
    private LocalDateTime expireTime;
    private Integer maxUses;
    private Integer usedCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
