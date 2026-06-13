package uno.acloud.common.oss;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class AvatarUploadSignRequest {
    @NotBlank(message = "头像文件名不能为空")
    private String fileName;
    @NotNull(message = "头像文件大小不能为空")
    @Positive(message = "头像文件大小必须大于 0")
    private Long fileSize;
    private String contentType;
}
