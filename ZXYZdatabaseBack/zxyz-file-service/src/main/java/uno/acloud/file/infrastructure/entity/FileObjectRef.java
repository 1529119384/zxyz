package uno.acloud.file.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("file_object_ref")
public class FileObjectRef implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "object_key", type = IdType.INPUT)
    private String objectKey;

    /** 存储提供者标识 */
    private String storageProvider;

    private Integer refCount;
    private String deleteStatus;
    private Integer deleteRetryCount;
    private LocalDateTime nextRetryTime;
    private String lastDeleteError;
    private LocalDateTime createTime;
    private LocalDateTime modifyTime;
    private LocalDateTime deleteTime;
}
