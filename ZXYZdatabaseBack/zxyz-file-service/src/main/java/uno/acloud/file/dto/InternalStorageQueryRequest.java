package uno.acloud.file.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
public class InternalStorageQueryRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long teamId;
    private Integer spaceType;
    private Long projectId;
    private List<Long> userIds;
    private List<Long> teamIds;
}
