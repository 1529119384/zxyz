package uno.acloud.common.oss;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConditionalOnClass(CredentialsProvider.class)
public class OSSClientConfig {

    private static final String SIGNATURE_VERSION_V4 = "v4";
    private static final List<String> ADDITIONAL_SIGNED_HEADERS = List.of("Content-Disposition");

    @Bean
    public CredentialsProvider ossCredentialsProvider() {
        return new EnvironmentVariableCredentialsProvider();
    }

    @Bean(destroyMethod = "close")
    public OSSClient ossClient(OSSProperties ossProperties, CredentialsProvider ossCredentialsProvider) {
        var builder = OSSClient.newBuilder()
                .credentialsProvider(ossCredentialsProvider)
                .region(ossProperties.getRegion())
                .signatureVersion(SIGNATURE_VERSION_V4)
                .additionalHeaders(ADDITIONAL_SIGNED_HEADERS);

        if (ossProperties.getEndpoint() != null && !ossProperties.getEndpoint().isBlank()) {
            builder.endpoint(ossProperties.getEndpoint());
        }

        return builder.build();
    }
}
