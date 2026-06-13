package uno.acloud.share.vo;

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
@Schema(description = "分享验证响应")
public class ShareVerifyResponseVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否验证通过")
    private Boolean passed;
}
