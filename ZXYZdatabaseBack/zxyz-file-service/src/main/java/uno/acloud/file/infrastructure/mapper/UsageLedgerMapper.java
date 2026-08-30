package uno.acloud.file.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.file.infrastructure.entity.UsageLedger;

import java.util.List;

/**
 * 配额台账 Mapper（P2-C2）。
 * <p>
 * 增量发生在文件 confirm 的同一事务内（见 FileUploadPersistenceManager.saveFileItem）。
 */
@Mapper
public interface UsageLedgerMapper extends BaseMapper<UsageLedger> {

    /**
     * 确保作用域行存在并写入/更新存储上限：
     * <ul>
     *   <li>行不存在 → 插入（storage_limit 取本次解析的 limit，可为 NULL=无限）。</li>
     *   <li>行已存在 → 仅在传入 limit 非 NULL 时更新 storage_limit（避免用 NULL 覆盖已配置上限）。</li>
     * </ul>
     * 在 confirm 前由配额预检结果埋入 limit，供后续原子扣减守卫使用。
     */
    @Insert("""
            INSERT INTO usage_ledger (scope_key, used_bytes, storage_limit, update_time)
            VALUES (#{scopeKey}, 0, #{limit}, NOW(3))
            ON DUPLICATE KEY UPDATE
                storage_limit = IF(VALUES(storage_limit) IS NULL, storage_limit, VALUES(storage_limit)),
                update_time = NOW(3)
            """)
    int ensureScopeAndLimit(@Param("scopeKey") String scopeKey, @Param("limit") Long limit);

    /**
     * 原子扣减守卫：仅当累计未超上限时把 used_bytes 增加 {@code bytes}。
     * <p>storage_limit 为 NULL 视为不限制；超限时受影响行数为 0，调用方应据此拒绝（并回滚同事务）。
     *
     * @return 成功 1；超限/作用域缺失 0
     */
    @Update("""
            UPDATE usage_ledger
            SET used_bytes = used_bytes + #{bytes}, update_time = NOW(3)
            WHERE scope_key = #{scopeKey}
              AND (storage_limit IS NULL OR used_bytes + #{bytes} <= storage_limit)
            """)
    int incrementWhenUnderLimit(@Param("scopeKey") String scopeKey, @Param("bytes") long bytes);

    /**
     * 原子减量（彻底删除/清理时释放配额）。作用域行缺失时忽略（对账会校正）。
     */
    @Update("""
            UPDATE usage_ledger
            SET used_bytes = GREATEST(used_bytes - #{bytes}, 0), update_time = NOW(3)
            WHERE scope_key = #{scopeKey}
            """)
    int decrement(@Param("scopeKey") String scopeKey, @Param("bytes") long bytes);

    @Select("SELECT * FROM usage_ledger WHERE scope_key = #{scopeKey}")
    UsageLedger getByScopeKey(@Param("scopeKey") String scopeKey);

    /**
     * 对账：用权威值覆盖 used_bytes（不覆盖 storage_limit）。
     */
    @Insert("""
            INSERT INTO usage_ledger (scope_key, used_bytes, update_time)
            VALUES (#{scopeKey}, #{bytes}, NOW(3))
            ON DUPLICATE KEY UPDATE
                used_bytes = VALUES(used_bytes),
                update_time = NOW(3)
            """)
    int upsertUsedForReconcile(@Param("scopeKey") String scopeKey, @Param("bytes") long bytes);

    @Select("SELECT scope_key, used_bytes, storage_limit, update_time FROM usage_ledger")
    List<UsageLedger> selectAll();
}