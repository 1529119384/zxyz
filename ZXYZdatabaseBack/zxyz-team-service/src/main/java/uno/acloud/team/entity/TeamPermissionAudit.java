package uno.acloud.team.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 团队权限审计记录实体
 */
@Getter
@Setter
@ToString
@TableName("team_permission_audit")
public class TeamPermissionAudit implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long operatorId;
    private String operationType;
    private String targetType;
    private Long targetId;
    private String beforeValue;
    private String afterValue;
    private LocalDateTime operationTime;
}
