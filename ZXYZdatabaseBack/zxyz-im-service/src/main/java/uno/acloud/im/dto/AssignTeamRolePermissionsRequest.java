package uno.acloud.im.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter @Setter @ToString
public class AssignTeamRolePermissionsRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "权限编码列表不能为空")
    private List<String> permissionCodes;
}
