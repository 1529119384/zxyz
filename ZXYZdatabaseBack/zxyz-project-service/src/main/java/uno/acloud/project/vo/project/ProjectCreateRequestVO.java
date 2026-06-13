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
@Schema(description = "项目创建申请信息")
public class ProjectCreateRequestVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "申请ID", example = "1")
    private Long id;
    @Schema(description = "所属团队ID")
    private Long teamId;
    @Schema(description = "申请人用户ID")
    private Long requesterUserId;
    @Schema(description = "申请人姓名", example = "张三")
    private String requesterName;
    @Schema(description = "项目名称", example = "我的项目")
    private String projectName;
    @Schema(description = "项目描述")
    private String description;
    @Schema(description = "负责人用户ID")
    private Long leaderUserId;
    @Schema(description = "负责人姓名", example = "李四")
    private String leaderName;
    @Schema(description = "存储配额上限，单位字节", example = "10737418240")
    private Long storageLimit;
    @Schema(description = "申请状态", example = "0")
    private Integer status;
    @Schema(description = "审批人用户ID")
    private Long reviewerUserId;
    @Schema(description = "审批时间")
    private LocalDateTime reviewTime;
    @Schema(description = "审批意见")
    private String reviewReason;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
