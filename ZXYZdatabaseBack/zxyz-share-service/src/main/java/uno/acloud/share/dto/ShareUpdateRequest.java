package uno.acloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "更新分享请求")
public class ShareUpdateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "分享状态")
    @NotNull(message = "分享状态不能为空")
    private Integer status;
}
