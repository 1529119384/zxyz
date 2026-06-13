package uno.acloud.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "用户设置更新请求")
public class UserSettingsRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Size(max = 64, message = "昵称不能超过 64 个字符")
    @Schema(description = "用户昵称", example = "张三")
    private String name;

    @Size(max = 512, message = "头像地址长度不能超过 512")
    @Schema(description = "头像地址")
    private String avatar;
}
