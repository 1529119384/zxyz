package uno.acloud.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "关联账号信任请求")
public class LinkedAccountTrustRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "当前账号密码")
    private String password;
}
