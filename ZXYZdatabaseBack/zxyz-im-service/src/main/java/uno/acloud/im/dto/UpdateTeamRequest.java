package uno.acloud.im.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
public class UpdateTeamRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "团队名称不能为空")
    private String name;
    private String avatar;
    private String description;
}
