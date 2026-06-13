package uno.acloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString(exclude = {"password"})
@Schema(description = "创建分享请求")
public class ShareCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "文件ID列表")
    @NotEmpty(message = "文件ID列表不能为空")
    private List<Long> fileIds;

    @Schema(description = "过期类型")
    @NotBlank(message = "过期类型不能为空")
    private String expireType;

    @Schema(description = "是否需要密码")
    private Boolean needPassword;

    @Schema(description = "访问密码")
    @Size(max = 4, message = "密码最多4个字符")
    private String password;

    @Schema(description = "是否自动填充密码")
    private Boolean autoFillPassword;

    @Schema(description = "最大访问次数")
    private Integer maxAccessCount;
}
