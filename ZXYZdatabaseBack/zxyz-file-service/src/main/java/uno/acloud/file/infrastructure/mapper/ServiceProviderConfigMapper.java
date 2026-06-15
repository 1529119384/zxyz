package uno.acloud.file.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import uno.acloud.file.infrastructure.entity.ServiceProviderConfig;

/**
 * 存储提供者配置 Mapper
 */
@Mapper
public interface ServiceProviderConfigMapper extends BaseMapper<ServiceProviderConfig> {
}
