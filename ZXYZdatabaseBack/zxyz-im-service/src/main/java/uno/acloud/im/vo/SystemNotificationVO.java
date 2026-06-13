package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
@AllArgsConstructor
public class SystemNotificationVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String type;
    private String title;
    private String content;
    private String businessType;
    private Long businessId;
    private Long teamId;
    private Integer status;
    private LocalDateTime readTime;
    private LocalDateTime createTime;
}
