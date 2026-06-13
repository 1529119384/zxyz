package uno.acloud.team.vo.team;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "管理后台团队概览")
public class AdminTeamOverviewVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "团队ID", example = "1")
    private Long id;
    @Schema(description = "团队名称", example = "我的团队")
    private String name;
    @Schema(description = "团队描述")
    private String description;
    @Schema(description = "拥有者用户ID")
    private Long ownerUserId;
    @Schema(description = "拥有者用户名", example = "admin")
    private String ownerUsername;
    @Schema(description = "当前成员数", example = "10")
    private Integer memberCount;
    @Schema(description = "成员上限", example = "50")
    private Integer memberLimit;
    @Schema(description = "存储配额上限，单位字节", example = "107374182400")
    private Long storageLimit;
    @Schema(description = "已用存储，单位字节", example = "53687091200")
    private Long usedStorage;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
