package uno.acloud.project.vo.permission;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
public class PermissionAuditVO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long userId;
    private String action;
    private String permissionCode;
    private Long operatorId;
    private String ipAddress;
    private LocalDateTime operateTime;
}
