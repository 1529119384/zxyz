package uno.acloud.file.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

/**
 * 内部分享创建前的文件归属/读权限校验请求（P0-3）。
 * <p>share-service 在创建分享前调用 POST /api/internal/files/share-access-check，
 * file-service 校验这些文件对指定用户均有读权限后返回成功。</p>
 */
@Getter
@Setter
@ToString
public class InternalShareAccessCheckRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "文件ID列表不能为空")
    private List<Long> fileIds;

    @NotNull(message = "用户ID不能为空")
    private Long userId;
}