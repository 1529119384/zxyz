package uno.acloud.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 内部接口返回的用户配额 VO，替代 Map<String, Object>
 */
@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "用户配额信息")
public class UserQuotaVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "存储配额上限（字节）")
    private Long storageLimit;
}
