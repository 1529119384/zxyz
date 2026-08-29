package uno.acloud.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
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

    /**
     * 携带 message_hash 插入审计日志。
     * <p>幂等下沉到 DB 唯一约束 uk_operate_log_message_hash：同一消息（同 message_hash）重复投递时
     * 唯一键冲突会抛出 {@link org.springframework.dao.DuplicateKeyException}，由消费端捕获并跳过。
     *
     * @param log         审计日志实体
     * @param messageHash 消息体 SHA-256 十六进制哈希（64 位，对应 message_hash CHAR(64)）
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO operate_log (service_name, operate_user, operate_time, class_name, method_name,
                method_params, return_value, before_value, after_value, cost_time, message_hash)
            VALUES (#{log.serviceName}, #{log.operateUser}, #{log.operateTime}, #{log.className}, #{log.methodName},
                   #{log.methodParams}, #{log.returnValue}, #{log.beforeValue}, #{log.afterValue}, #{log.costTime}, #{messageHash})
            """)
    int insertWithHash(@Param("log") OperateLog log, @Param("messageHash") String messageHash);
}
