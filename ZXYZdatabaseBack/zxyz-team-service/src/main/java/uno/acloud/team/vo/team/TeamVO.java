package uno.acloud.team.vo.team;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "团队信息")
public class TeamVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "团队ID", example = "1")
    private Long id;
    @Schema(description = "团队名称", example = "我的团队")
    private String name;
    @Schema(description = "团队头像URL")
    private String avatar;
    @Schema(description = "团队描述")
    private String description;
    @Schema(description = "团队拥有者用户ID")
    private Long ownerUserId;
    @Schema(description = "团队状态", example = "1")
    private Integer status;
    @Schema(description = "当前用户的角色编码", example = "admin")
    private String myRoleCode;
    @Schema(description = "当前用户的权限编码列表")
    private List<String> myPermissions;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
