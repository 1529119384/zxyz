package uno.acloud.file.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
public class InternalBatchParentIdsRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "父节点ID列表不能为空")
    private List<Long> parentIds;
}
