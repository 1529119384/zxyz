package uno.acloud.im.dto;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
public class CreateInviteLinkRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Positive(message = "过期时间必须为正数")
    private Integer expireHours;
    @Positive(message = "最大使用次数必须为正数")
    private Integer maxUses;
}
