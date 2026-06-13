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
@Schema(description = "创建分享响应")
public class ShareCreateResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "分享ID")
    private Long shareId;

    @Schema(description = "分享Key")
    private String shareKey;

    @Schema(description = "访问密码")
    private String password;

    @Schema(description = "分享链接")
    private String shareUrl;

    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "最大访问次数")
    private Integer maxAccessCount;
}
