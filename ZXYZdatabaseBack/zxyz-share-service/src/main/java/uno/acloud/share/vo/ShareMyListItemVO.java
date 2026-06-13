package uno.acloud.share.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "我的分享列表项")
public class ShareMyListItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "分享ID")
    private Long shareId;

    @Schema(description = "分享Key")
    private String shareKey;

    @Schema(description = "分享链接")
    private String shareUrl;

    @Schema(description = "是否设置了密码")
    private boolean hasPassword;

    @Schema(description = "过期类型")
    private String expireType;

    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "最大访问次数")
    private Integer maxAccessCount;

    @Schema(description = "当前访问次数")
    private Integer currentAccessCount;

    @Schema(description = "分享状态")
    private Integer status;

    @Schema(description = "分享状态文本")
    private String statusText;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
