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
@Schema(description = "分享下载响应")
public class ShareDownloadResponseVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "下载链接")
    private String downloadUrl;
}
