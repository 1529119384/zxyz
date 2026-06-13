package uno.acloud.project.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
public class UserQuota implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private Long storageLimit;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
