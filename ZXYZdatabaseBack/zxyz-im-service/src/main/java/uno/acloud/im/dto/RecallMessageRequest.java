package uno.acloud.im.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
public class RecallMessageRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String reason;
}
