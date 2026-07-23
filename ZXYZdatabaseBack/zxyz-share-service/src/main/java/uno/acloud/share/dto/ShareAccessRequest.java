package uno.acloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString(exclude = {"password"})
@Schema(description = "分享访问请求")
public class ShareAccessRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "访问密码")
    @Size(max = 8, message = "密码最多8个字符")
    private String password;
}
