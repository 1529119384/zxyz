package uno.acloud.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import uno.acloud.admin.domain.SysConfigAudit;

/**
 * 系统配置审计日志 Mapper
 */
@Mapper
public interface SysConfigAuditMapper extends BaseMapper<SysConfigAudit> {

    /**
     * 插入配置变更审计记录
     *
     * @param configKey 配置键
     * @param oldValue  旧值
     * @param newValue  新值
     * @param changedBy 操作人 ID
     * @return 受影响行数
     */
    @Insert("INSERT INTO sys_config_audit (config_key, old_value, new_value, changed_by, changed_at) " +
            "VALUES (#{configKey}, #{oldValue}, #{newValue}, #{changedBy}, NOW())")
    int insert(@Param("configKey") String configKey,
               @Param("oldValue") String oldValue,
               @Param("newValue") String newValue,
               @Param("changedBy") Long changedBy);
}
