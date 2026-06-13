package uno.acloud.share.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "我的分享列表响应")
public class ShareMyListResponseVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "总数")
    private Integer total;

    @Schema(description = "分享列表")
    private List<ShareMyListItemVO> rows;
}
