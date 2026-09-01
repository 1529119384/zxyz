package uno.acloud.im.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import uno.acloud.im.domain.ImConversationStatus;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@TableName("im_conversation")
public class ImConversation implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String type;
    private Long teamId;
    private Long projectId;
    private String name;
    private String bizKey;
    private Long directUserA;
    private Long directUserB;
    private Integer status;
    private Boolean readOnly;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ===== 领域行为（状态机 / 不变量） =====

    /**
     * 新建会话时的初始化：状态置为 ACTIVE，设为可写，并写入创建/更新时间。
     * 类型相关字段（type/teamId/bizKey 等）由调用方各自 setter 设置。
     */
    public void markActive() {
        this.status = ImConversationStatus.ACTIVE;
        this.readOnly = false;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    /** 是否处于活跃状态。 */
    public boolean isActive() {
        return ImConversationStatus.ACTIVE == this.status;
    }

    /** 是否已被归档（以 readOnly 标记）。 */
    public boolean isArchived() {
        return Boolean.TRUE.equals(this.readOnly);
    }

    /** 是否只读。 */
    public boolean isReadOnly() {
        return Boolean.TRUE.equals(this.readOnly);
    }
}
