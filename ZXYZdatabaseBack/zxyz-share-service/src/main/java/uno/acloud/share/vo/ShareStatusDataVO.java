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
@Schema(description = "分享状态数据")
public class ShareStatusDataVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "状态码")
    private Integer status;

    @Schema(description = "状态文本")
    private String statusText;
}
