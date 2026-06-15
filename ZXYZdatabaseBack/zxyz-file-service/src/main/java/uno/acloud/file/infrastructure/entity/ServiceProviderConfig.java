package uno.acloud.file.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 存储提供者配置实体
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("storage_provider_config")
public class ServiceProviderConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 提供者标识 */
    private String providerId;

    /** 显示名称 */
    private String displayName;

    /** 是否启用 */
    private Boolean enabled;

    /** 是否默认存储 */
    private Boolean isDefault;

    /** 提供者配置（JSON） */
    private String configJson;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 修改时间 */
    private LocalDateTime modifyTime;
}
