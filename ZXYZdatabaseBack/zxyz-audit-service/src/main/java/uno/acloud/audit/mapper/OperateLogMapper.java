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
     * 分批删除指定时间之前的审计日志记录（用于定期清理过期数据）。
     *
     * <p>54 号审计 🟡：清理任务曾一次性 DELETE 全量过期行 —— 积压恢复（或保留天数调小）后
     * 单条 DELETE 走 idx_ol_time 索引删除数十万行，binlog/undo 膨胀、主从延迟抖动，
     * 还会长时间占住行锁。现改为「LIMIT 1000 + affected==0 退出」的批次协议，
     * 由 {@code AuditLogCleanupService} 循环调用、事务按批提交。</p>
     *
     * <p>⚠️ 为什么显式列出 WHERE 走的索引列而不写 ORDER BY：MySQL 的 DELETE ... LIMIT
     * 在 WHERE 命中索引（idx_ol_time）时删除顺序即索引序，无需 ORDER BY 兜底；
     * 但若未来有人删掉 operate_time 的索引，这里会退化为全表扫描锁 —— 由
     * {@code AuditLogCleanupServiceBatchTest} 的批次协议测试与注释共同守护。</p>
     *
     * @param cutoff 删除此时间之前的记录
     * @return 本批实际删除的记录数（0 = 该时间点之前已无过期数据，批次循环应退出）
     */
    @Delete("DELETE FROM operate_log WHERE operate_time < #{cutoff} LIMIT #{limit}")
    int deleteOlderThanBatch(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 携带 message_hash 插入审计日志。
     * <p>幂等下沉到 DB 唯一约束 uk_operate_log_message_hash：同一消息（同 message_hash）重复投递时
     * 唯一键冲突会抛出 {@link org.springframework.dao.DuplicateKeyException}，由消费端捕获并跳过。
     *
     * <p><b>与实体字段的关系（54 号审计 🔴-1 收敛）</b>：{@code OperateLog} 实体现已补齐
     * {@code messageHash} 字段，走 MyBatis-Plus 公共 {@code insert()} 也会把该列写入，
     * 本方法不再承担「显式列清单绕过实体缺口」的职责 —— 保留它是因为消费端把
     * 「对线上原始消息体算 SHA-256」这一步与 insert 绑在同一调用里，语义更直白；
     * 调用方须保证 {@code messageHash} 与 {@code log.getMessageHash()} 一致
     * （由 {@code AuditContextSmokeTest} 的持久化路径测试钉住）。</p>
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
