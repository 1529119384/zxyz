package uno.acloud.common.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@AllArgsConstructor
@NoArgsConstructor
@TableName("operate_log")
public class OperateLog implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String serviceName;
    private Long operateUser;
    private LocalDateTime operateTime;
    private String className;
    private String methodName;
    private String methodParams;
    private String returnValue;
    private String beforeValue;
    private String afterValue;
    private Long costTime;
    /**
     * 消息体 SHA-256 十六进制哈希（64 位），对应 operate_log.message_hash CHAR(64)。
     *
     * <p>幂等锚点：DB 唯一约束 uk_operate_log_message_hash 靠它去重。历史上实体长期
     * 缺这个字段（54 号审计 🔴-1），哈希只能靠 OperateLogMapper.insertWithHash 的显式
     * 列清单旁路写入 —— 任何走 MyBatis-Plus 公共 insert() 的代码都会把 message_hash
     * 静默 NULL 化（唯一索引允许多 NULL ⇒ 幂等失效、重复消息可多条入库）。补上本字段后
     * 实体与 DDL 对齐，公共 insert() 不再是幂等炸弹。哈希由 audit-service 的
     * OperateLogConsumer 在消费入口计算填充，发布端无需关心。</p>
     */
    private String messageHash;
}
