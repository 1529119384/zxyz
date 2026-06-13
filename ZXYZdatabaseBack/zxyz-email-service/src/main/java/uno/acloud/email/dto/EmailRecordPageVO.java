package uno.acloud.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;
import uno.acloud.email.vo.EmailRecordVO;

import java.io.Serializable;
import java.util.List;

@Getter @Setter @ToString
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "邮件记录分页响应")
public class EmailRecordPageVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "总记录数")
    private Long total;

    @Schema(description = "当前页码")
    private Integer page;

    @Schema(description = "每页大小")
    private Integer pageSize;

    @Schema(description = "邮件记录列表")
    private List<EmailRecordVO> records;
}
