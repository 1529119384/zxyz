package uno.acloud.team.vo.permission;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 团队权限审计视图对象
 */
@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "团队权限审计记录")
public class TeamPermissionAuditVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "记录ID", example = "1")
    private Long id;
    @Schema(description = "团队ID")
    private Long teamId;
    @Schema(description = "操作人用户ID")
    private Long operatorId;
    @Schema(description = "操作类型", example = "ASSIGN_ROLE")
    private String operationType;
    @Schema(description = "目标类型", example = "MEMBER")
    private String targetType;
    @Schema(description = "目标ID")
    private Long targetId;
    @Schema(description = "变更前值")
    private String beforeValue;
    @Schema(description = "变更后值")
    private String afterValue;
    @Schema(description = "操作时间")
    private LocalDateTime operationTime;
}
