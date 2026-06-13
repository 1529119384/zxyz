package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
@AllArgsConstructor
public class TeamPermissionAuditVO implements Serializable {
    private static final long serialVersionUID = 1L;

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
