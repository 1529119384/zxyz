package uno.acloud.file.dto.im;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
public class FileCardResolveRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "分享类型不能为空")
    private String shareType;
    @NotNull(message = "拥有者用户ID不能为空")
    private Long ownerUserId;
    private Long parentId;
    private Integer entryCount;
    @Valid
    @NotEmpty(message = "文件条目列表不能为空")
    private List<FileCardEntryRequest> entries;
}
