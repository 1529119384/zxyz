package uno.acloud.user.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
public class InternalBatchUserRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "用户 ID 列表不能为空")
    private List<Long> userIds;
}
