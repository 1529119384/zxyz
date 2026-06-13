package uno.acloud.team.dto.system;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
public class BroadcastSystemMessageRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题长度不能超过200")
    private String title;
    @NotBlank(message = "内容不能为空")
    private String content;
}
