package uno.acloud.im.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
public class CreateTeamRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "团队名称不能为空")
    @Size(max = 50, message = "团队名称长度1-50个字符")
    private String name;
    private String avatar;
    private String description;
}
