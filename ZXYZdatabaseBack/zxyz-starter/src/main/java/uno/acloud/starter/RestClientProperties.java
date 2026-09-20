package uno.acloud.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务间 RestClient 的基础设施层超时参数（P2-2：把原先散在三处的硬编码 3s / 10s 收敛为一处可配）。
 *
 * <p><b>默认值与提取前的硬编码逐字一致</b>（连接 3s / 读取 10s）⇒ 本类不改变任何现存行为，
 * 只是让运维可以按环境调整而不必改代码。</p>
 *
 * <p>定位沿用 {@link RestClientAutoConfiguration} 的既有判断：这是<b>基础设施层参数</b>，
 * <b>不接入 ConfigGetter 热配置</b>。需要调整时改 {@code application-common.yml}
 * 或设环境变量 {@code HTTP_CLIENT_CONNECT_TIMEOUT_SECONDS} / {@code HTTP_CLIENT_READ_TIMEOUT_SECONDS}，
 * 重启生效。</p>
 */
@ConfigurationProperties(prefix = "zxyz.http-client")
public class RestClientProperties {

    /** 建立 TCP 连接的超时（秒）。 */
    private int connectTimeoutSeconds = 3;

    /** 等待响应体的读取超时（秒）。 */
    private int readTimeoutSeconds = 10;

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }
}
