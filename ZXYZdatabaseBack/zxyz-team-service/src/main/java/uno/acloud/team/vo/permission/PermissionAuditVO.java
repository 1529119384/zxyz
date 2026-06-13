package uno.acloud.team.vo.permission;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@AllArgsConstructor
public class PermissionAuditVO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long operatorId;
    private String scopeType;
    private String operationType;
    private String targetType;
    private Long targetId;
    private String beforeValue;
    private String afterValue;
    private LocalDateTime operationTime;
    private String ipAddress;
}
