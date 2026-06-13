package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter @Setter @ToString
@AllArgsConstructor
public class TeamRoleVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String roleName;
    private String roleCode;
    private String description;
    private Boolean builtin;
    private List<String> permissionCodes;
}
