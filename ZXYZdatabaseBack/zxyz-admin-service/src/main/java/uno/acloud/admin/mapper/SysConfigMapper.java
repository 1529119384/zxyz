package uno.acloud.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.admin.domain.SysConfig;

/**
 * 系统配置 Mapper
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {

    /**
     * 根据配置键查询配置
     *
     * @param key 配置键
     * @return 配置实体，不存在时返回 null
     */
    @Select("SELECT * FROM sys_config WHERE config_key = #{key}")
    SysConfig selectByKey(@Param("key") String key);

    /**
     * 更新配置值
     *
     * @param key   配置键
     * @param value 新配置值
     * @return 受影响行数
     */
    @Update("UPDATE sys_config SET config_value = #{value}, updated_at = NOW() WHERE config_key = #{key}")
    int updateValue(@Param("key") String key, @Param("value") String value);
}
