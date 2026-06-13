package uno.acloud.im.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.io.Serializable;
import java.util.List;

@Getter @Setter @ToString
@AllArgsConstructor
@NoArgsConstructor
public class TeamVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String avatar;
    private String description;
    private Long ownerUserId;
    private String myRoleCode;
    private List<String> myPermissions;
    private LocalDateTime createTime;
}
