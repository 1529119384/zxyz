package uno.acloud.team.vo.permission;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@AllArgsConstructor
public class RoleItemVO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Integer id;
    private String roleName;
    private String roleCode;
    private String description;
    private List<String> permissionCodes;
}
