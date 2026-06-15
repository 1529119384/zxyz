package uno.acloud.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统运行时配置实体
 */
@Data
@TableName("sys_config")
public class SysConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 配置键
     */
    private String configKey;

    /**
     * 配置值
     */
    private String configValue;

    /**
     * 类型：SYSTEM/FEATURE/SECURITY
     */
    private String configType;

    /**
     * 值类型：STRING/NUMBER/BOOLEAN/JSON
     */
    private String valueType;

    /**
     * 说明
     */
    private String description;

    /**
     * 是否加密存储
     */
    private Boolean isEncrypted;

    /**
     * 是否可在 Admin UI 编辑
     */
    private Boolean isEditable;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 校验规则（正则或枚举）
     */
    private String validationRule;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
