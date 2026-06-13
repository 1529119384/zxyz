package uno.acloud.team.dto.team;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "添加团队成员请求")
public class CreateTeamMemberRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotBlank(message = "用户名不能为空")
    @Schema(description = "用户名", example = "zhangsan")
    private String username;
    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码", example = "password123")
    private String password;
    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名长度不能超过50")
    @Schema(description = "姓名", example = "张三")
    private String name;
    @Size(max = 50, message = "角色编码长度不能超过50")
    @Schema(description = "角色编码", example = "member")
    private String roleCode;
}
