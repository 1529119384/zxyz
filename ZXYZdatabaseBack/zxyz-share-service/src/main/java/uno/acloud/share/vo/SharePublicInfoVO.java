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
@Schema(description = "公开分享信息")
public class SharePublicInfoVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "分享Key")
    private String shareKey;

    @Schema(description = "分享者用户名")
    private String username;

    @Schema(description = "展示用用户名")
    private String showUsername;

    @Schema(description = "是否需要密码")
    private Boolean needPassword;

    @Schema(description = "是否已验证通过")
    private Boolean passed;

    @Schema(description = "是否可查看内容")
    private Boolean canViewContent;

    @Schema(description = "分享状态")
    private Integer status;

    @Schema(description = "分享状态文本")
    private String statusText;
}
