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
@Schema(description = "文件搜索结果")
public class FileSearchResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "匹配总数", example = "10")
    private Long total;

    @Schema(description = "搜索结果列表")
    private List<FileSearchItemVO> list;
}
