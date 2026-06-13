package uno.acloud.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString(exclude = {"token"})
@AllArgsConstructor
@Schema(description = "登录响应")
public class LoginVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "访问令牌")
    private String token;

    @Schema(description = "令牌类型", example = "Bearer")
    private String tokenType;

    @Schema(description = "是否登录成功")
    private Boolean isLogin;
}
