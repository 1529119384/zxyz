package uno.acloud.common.oss;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@ToString
@Component
@ConfigurationProperties(prefix = "app.oss")
public class OSSProperties {
    private String region;
    private String bucket;
    private String endpoint;
    private String publicBaseUrl;
}
