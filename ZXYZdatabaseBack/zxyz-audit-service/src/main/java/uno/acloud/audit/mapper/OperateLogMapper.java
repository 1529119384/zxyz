package uno.acloud.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import uno.acloud.common.audit.OperateLog;

import java.time.LocalDateTime;

@Mapper
public interface OperateLogMapper extends BaseMapper<OperateLog> {

    /**
     * 删除指定时间之前的审计日志记录（用于定期清理过期数据）。
     *
     * @param cutoff 删除此时间之前的所有记录
     * @return 删除的记录数
     */
    @Delete("DELETE FROM operate_log WHERE operate_time < #{cutoff}")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
