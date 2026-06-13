package uno.acloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "分享验证请求")
public class ShareVerifyRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "分享Key")
    @NotBlank(message = "分享Key不能为空")
    private String shareKey;

    @Schema(description = "访问密码")
    @Size(max = 4, message = "密码最多4个字符")
    private String password;
}
