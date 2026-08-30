package uno.acloud.file.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配额台账：每个存储作用域一行（P{项目id}/T{团队id}/U{用户id}），
 * 作为配额"检查+扣减"原子化的唯一权威（P2-C2）。
 * <p>
 * used_bytes 与 file_node 的 {@code scope_key} 生成列对齐：入库按同样规则计算，
 * 后台对账用 {@code SUM(file_size) WHERE deleted IN (0,1) GROUP BY scope_key} 校正。
 */
@Data
@TableName("usage_ledger")
public class UsageLedger {

    private String scopeKey;

    private Long usedBytes;

    /** 存储上限字节；NULL=不限制（由校验时的配额解析结果写入，对账不覆盖）。 */
    private Long storageLimit;

    private LocalDateTime updateTime;

    /**
     * 与 file_node.scope_key 生成列严格对齐的作用域键。
     * <p>项目空间取 P{projectId}，团队空间取 T{teamId}，否则个人空间取 U{uploadUserId}。
     */
    public static String scopeKeyOf(Integer spaceType, Long teamId, Long projectId, Long ownerUserId) {
        Integer normalized = spaceType == null ? 1 : spaceType;
        if (normalized == 3 && projectId != null) {
            return "P" + projectId;
        }
        if (normalized == 2 && teamId != null) {
            return "T" + teamId;
        }
        return "U" + (ownerUserId == null ? 0L : ownerUserId);
    }
}