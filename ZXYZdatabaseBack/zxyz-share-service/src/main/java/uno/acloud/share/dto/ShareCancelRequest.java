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
@Schema(description = "取消分享请求")
public class ShareCancelRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "分享ID")
    @NotNull(message = "分享ID不能为空")
    private Long shareId;
}
