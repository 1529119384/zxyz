package uno.acloud.project.entity;

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
@TableName("project_create_request")
public class ProjectCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long requesterUserId;
    private String projectName;
    private String description;
    private Long leaderUserId;
    private Long storageLimit;
    private Integer status;
    private Long reviewerUserId;
    private LocalDateTime reviewTime;
    private String reviewReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
