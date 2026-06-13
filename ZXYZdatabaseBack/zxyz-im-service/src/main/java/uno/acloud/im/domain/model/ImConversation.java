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
}
