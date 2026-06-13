package uno.acloud.im.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

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
}
