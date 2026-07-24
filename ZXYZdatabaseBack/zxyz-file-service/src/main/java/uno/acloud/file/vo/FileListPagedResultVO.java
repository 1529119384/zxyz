package uno.acloud.file.vo;

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
@Schema(description = "文件列表分页结果")
public class FileListPagedResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "当前页码", example = "1")
    private Integer page;

    @Schema(description = "每页大小", example = "50")
    private Integer pageSize;

    @Schema(description = "子项总数", example = "100")
    private Long total;

    @Schema(description = "当前页文件列表")
    private List<FileListItemVO> list;
}
