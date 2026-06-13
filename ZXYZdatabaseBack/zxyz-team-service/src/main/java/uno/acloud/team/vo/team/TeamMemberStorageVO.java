package uno.acloud.team.vo.team;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "团队成员存储用量")
public class TeamMemberStorageVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "用户ID", example = "1")
    private Long userId;
    @Schema(description = "用户名", example = "zhangsan")
    private String username;
    @Schema(description = "姓名", example = "张三")
    private String name;
    @Schema(description = "头像URL")
    private String avatar;
    @Schema(description = "角色编码", example = "member")
    private String roleCode;
    @Schema(description = "已用个人存储，单位字节", example = "5368709120")
    private long personalStorageUsed;
    @Schema(description = "个人存储配额上限，单位字节", example = "10737418240")
    private Long personalStorageLimit;
}
