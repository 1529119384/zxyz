package uno.acloud.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置变更审计实体
 */
@Data
@TableName("sys_config_audit")
public class SysConfigAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 配置键
     */
    private String configKey;

    /**
     * 旧值
     */
    private String oldValue;

    /**
     * 新值
     */
    private String newValue;

    /**
     * 操作人 ID
     */
    private Long changedBy;

    /**
     * 操作时间
     */
    private LocalDateTime changedAt;
}
