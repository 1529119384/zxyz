package uno.acloud.team.dto.permission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@EqualsAndHashCode(callSuper = true)
public class InternalRoleSaveRequest extends RoleUpsertRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotNull(message = "操作人 ID 不能为空")
    private Long operatorId;
    private Integer roleId;
    @NotBlank(message = "IP 地址不能为空")
    private String ipAddress;
}
