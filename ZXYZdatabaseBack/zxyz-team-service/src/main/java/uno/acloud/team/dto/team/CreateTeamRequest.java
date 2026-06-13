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
@Schema(description = "创建团队请求")
public class CreateTeamRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotBlank(message = "团队名称不能为空")
    @Size(max = 50, message = "团队名称长度不能超过50")
    @Schema(description = "团队名称", example = "我的团队")
    private String name;
    @Size(max = 500, message = "团队头像URL长度不能超过500")
    @Schema(description = "团队头像URL")
    private String avatar;
    @Size(max = 100, message = "团队描述长度不能超过100")
    @Schema(description = "团队描述", example = "团队描述内容")
    private String description;
    @NotNull(message = "成员上限不能为空")
    @Positive(message = "成员上限必须为正数")
    @Schema(description = "成员上限", example = "50")
    private Integer memberLimit;
    @NotNull(message = "存储配额上限不能为空")
    @Positive(message = "存储配额上限必须为正数")
    @Schema(description = "存储配额上限，单位字节", example = "107374182400")
    private Long storageLimit;
    @NotBlank(message = "团队拥有者用户名不能为空")
    @Schema(description = "团队拥有者用户名", example = "admin")
    private String ownerUsername;
    @NotBlank(message = "团队拥有者密码不能为空")
    @Schema(description = "团队拥有者密码", example = "password123")
    private String ownerPassword;
    @Size(max = 50, message = "团队拥有者姓名长度不能超过50")
    @Schema(description = "团队拥有者姓名", example = "张三")
    private String ownerName;
    @Size(max = 200, message = "邮箱长度不能超过200")
    @Schema(description = "团队拥有者邮箱", example = "admin@example.com")
    private String ownerEmail;
    @Size(max = 20, message = "手机号长度不能超过20")
    @Schema(description = "团队拥有者手机号", example = "13800138000")
    private String ownerPhone;
}
