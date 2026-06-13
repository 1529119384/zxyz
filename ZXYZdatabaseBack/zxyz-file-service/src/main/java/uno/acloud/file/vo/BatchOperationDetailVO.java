package uno.acloud.file.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批量操作详情")
public class BatchOperationDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "总数", example = "5")
    private Integer totalCount;

    @Schema(description = "成功数", example = "3")
    private Integer successCount;

    @Schema(description = "失败数", example = "1")
    private Integer failCount;

    @Schema(description = "跳过数", example = "1")
    private Integer skippedCount;

    @Schema(description = "重命名数", example = "0")
    private Integer renamedCount;

    @Schema(description = "目标父目录ID")
    private Long targetParentId;

    @Schema(description = "各项操作详情")
    private List<ItemDetail> details;

    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "批量操作单项详情")
    public static class ItemDetail implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "文件ID")
        private Long fileId;

        @Schema(description = "文件名", example = "文档.txt")
        private String fileName;

        @Schema(description = "文件类型", example = "2")
        private Integer fileType;

        @Schema(description = "执行的操作", example = "copy")
        private String action;

        @Schema(description = "是否被重命名", example = "false")
        private Boolean renamed;

        @Schema(description = "最终文件名", example = "文档(1).txt")
        private String finalName;

        @Schema(description = "处理状态", example = "success")
        private String status;

        @Schema(description = "业务状态码", example = "0")
        private Integer code;

        @Schema(description = "提示信息")
        private String msg;
    }
}
