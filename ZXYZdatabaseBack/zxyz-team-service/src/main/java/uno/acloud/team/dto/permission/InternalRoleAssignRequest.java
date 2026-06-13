package uno.acloud.team.dto.permission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
public class InternalRoleAssignRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;
    @NotBlank(message = "角色编码不能为空")
    private String roleCode;
    @NotNull(message = "操作人 ID 不能为空")
    private Long operatorId;
    @NotBlank(message = "IP 地址不能为空")
    private String ipAddress;
}
