package uno.acloud.im.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
public class InternalTeamSyncRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "团队ID不能为空")
    private Long teamId;
    @NotBlank(message = "团队名称不能为空")
    private String name;
    private String avatar;
    private String description;
    private Long ownerUserId;
    private String ownerUsername;
    private String ownerName;
    private String ownerEmail;
}
