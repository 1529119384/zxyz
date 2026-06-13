package uno.acloud.team.vo.team;

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
@Schema(description = "团队成员信息")
public class TeamMemberVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "用户ID", example = "1")
    private Long userId;
    @Schema(description = "用户名", example = "zhangsan")
    private String username;
    @Schema(description = "姓名", example = "张三")
    private String name;
    @Schema(description = "头像URL")
    private String avatar;
    @Schema(description = "邮箱", example = "user@example.com")
    private String email;
    @Schema(description = "角色编码", example = "member")
    private String roleCode;
    @Schema(description = "成员状态", example = "1")
    private Integer status;
    @Schema(description = "加入时间")
    private LocalDateTime joinTime;
}
