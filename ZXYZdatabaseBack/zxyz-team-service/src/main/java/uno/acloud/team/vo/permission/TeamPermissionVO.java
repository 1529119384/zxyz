package uno.acloud.team.vo.permission;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 团队权限定义视图对象
 */
@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "团队权限信息")
public class TeamPermissionVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "权限ID", example = "1")
    private Integer id;
    @Schema(description = "权限名称", example = "文件管理")
    private String permissionName;
    @Schema(description = "权限编码", example = "file.manage")
    private String permissionCode;
    @Schema(description = "权限描述")
    private String description;
}
