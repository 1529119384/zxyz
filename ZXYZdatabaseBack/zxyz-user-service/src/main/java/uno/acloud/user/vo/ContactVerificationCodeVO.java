package uno.acloud.user.vo;

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
@Schema(description = "联系方式验证码信息")
public class ContactVerificationCodeVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "验证类型（email 或 phone）")
    private String type;

    @Schema(description = "验证码")
    private String code;
}
