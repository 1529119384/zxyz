package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
@AllArgsConstructor
public class TeamJoinRequestVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long teamId;
    private Long userId;
    private String username;
    private String name;
    private Integer status;
    private LocalDateTime createTime;
}
