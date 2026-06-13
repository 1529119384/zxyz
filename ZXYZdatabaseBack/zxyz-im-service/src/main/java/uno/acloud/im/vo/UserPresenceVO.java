package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
@AllArgsConstructor
public class UserPresenceVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long userId;
    private Boolean online;
    private Integer connectionCount;
    private LocalDateTime lastActiveTime;
}
