package uno.acloud.im.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter @Setter @ToString
public class InternalBatchSystemNotificationRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "用户ID列表不能为空")
    private List<Long> userIds;
    @NotBlank(message = "通知类型不能为空")
    private String type;
    @NotBlank(message = "通知标题不能为空")
    private String title;
    @NotBlank(message = "通知内容不能为空")
    private String content;
    private String businessType;
    private Long businessId;
    private Long teamId;
}
