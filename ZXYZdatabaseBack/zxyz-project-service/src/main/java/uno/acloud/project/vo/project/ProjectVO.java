package uno.acloud.project.vo.project;

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
@Schema(description = "项目信息")
public class ProjectVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "项目ID", example = "1")
    private Long id;
    @Schema(description = "所属团队ID")
    private Long teamId;
    @Schema(description = "项目名称", example = "我的项目")
    private String name;
    @Schema(description = "项目描述")
    private String description;
    @Schema(description = "负责人用户ID")
    private Long leaderUserId;
    @Schema(description = "关联会话ID")
    private Long conversationId;
    @Schema(description = "项目状态", example = "1")
    private Integer status;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "存储配额上限，单位字节", example = "10737418240")
    private Long storageLimit;
    @Schema(description = "已用存储，单位字节", example = "5368709120")
    private Long usedStorage;
    @Schema(description = "当前用户是否可访问", example = "true")
    private Boolean accessible;
    @Schema(description = "当前用户是否可管理", example = "false")
    private Boolean manageable;
}
