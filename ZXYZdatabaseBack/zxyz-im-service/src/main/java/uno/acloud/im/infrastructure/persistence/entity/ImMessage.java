package uno.acloud.im.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import uno.acloud.im.domain.ImMessageStatus;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@TableName("im_message")
public class ImMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long senderUserId;
    private String messageType;
    private String content;
    private Integer status;
    private Long recallByUserId;
    private LocalDateTime recallTime;
    private String recallReason;
    private String clientMessageId;
    private LocalDateTime createTime;

    // ===== 领域行为（状态机 / 不变量） =====

    /**
     * 新建消息时的初始化：设置会话、发送者、类型、内容、客户端消息幂等ID，
     * 状态置为 STORED，并写入创建时间。业务规则集中在此，而非散落在调用方。
     */
    public void initializeNew(Long conversationId, Long senderUserId, String messageType, String content, String clientMessageId) {
        this.conversationId = conversationId;
        this.senderUserId = senderUserId;
        this.messageType = messageType;
        this.content = content;
        this.status = ImMessageStatus.STORED;
        this.clientMessageId = clientMessageId;
        this.createTime = LocalDateTime.now();
    }

    /**
     * 标记消息已撤回：状态置为 RECALLED，并记录撤回操作人、时间与原因。
     * 注意：并发守卫（WHERE status=0）由 Mapper 的自定义 UPDATE SQL 负责，此处仅更新内存实体。
     */
    public void markRecalled(Long operatorUserId, String reason) {
        this.status = ImMessageStatus.RECALLED;
        this.recallByUserId = operatorUserId;
        this.recallTime = LocalDateTime.now();
        this.recallReason = reason;
    }

    /** 是否处于已存储（未撤回）状态。 */
    public boolean isStored() {
        return ImMessageStatus.STORED == this.status;
    }

    /** 是否已被撤回。 */
    public boolean isRecalled() {
        return ImMessageStatus.RECALLED == this.status;
    }
}
