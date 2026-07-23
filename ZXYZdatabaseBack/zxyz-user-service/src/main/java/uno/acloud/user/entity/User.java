package uno.acloud.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import uno.acloud.common.util.MaskingSerializer;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString(exclude = {"password"})
@AllArgsConstructor
@NoArgsConstructor
@TableName("user")
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String username;
    private String name;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    @JsonSerialize(using = MaskingSerializer.class)
    private String email;
    @JsonSerialize(using = MaskingSerializer.class)
    private String phone;
    private String avatar;
    private Boolean emailVerified;
    private Boolean phoneVerified;
    private Long defaultTeamId;
    private LocalDateTime createTime;
    private LocalDateTime lastLoginTime;
    private LocalDateTime updateTime;
}
