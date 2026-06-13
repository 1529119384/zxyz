package uno.acloud.team.vo.team;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 团队成员详情视图对象（内部服务间调用）
 */
@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "团队成员详情")
public class TeamMemberDetailVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "团队ID")
    private Long teamId;
    @Schema(description = "用户ID", example = "1")
    private Long userId;
    @Schema(description = "角色编码", example = "member")
    private String roleCode;
    @Schema(description = "成员状态", example = "1")
    private Integer status;
    @Schema(description = "个人存储配额上限，单位字节", example = "10737418240")
    private Long personalStorageLimit;
    @Schema(description = "加入时间")
    private LocalDateTime joinTime;
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
