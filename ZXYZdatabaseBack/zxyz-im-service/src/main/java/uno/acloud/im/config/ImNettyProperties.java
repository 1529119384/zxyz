package uno.acloud.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.netty")
public class ImNettyProperties {

    private boolean enabled = true;
    private int port = 19090;
    private String websocketPath = "/ws";

    /**
     * 66 号二.3（2026-11-02 补）：读空闲超时（秒）。客户端在此时长内没有发送任何帧
     * （含 WebSocket PING）即判定为死连接并关闭，防止半开连接长期占用内存与 fd。
     * 0 = 禁用空闲超时（不推荐；旧行为是永不超时）。
     */
    private int readerIdleSeconds = 180;

    public int getReaderIdleSeconds() {
        return readerIdleSeconds;
    }

    public void setReaderIdleSeconds(int readerIdleSeconds) {
        this.readerIdleSeconds = readerIdleSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getWebsocketPath() {
        return websocketPath;
    }

    public void setWebsocketPath(String websocketPath) {
        this.websocketPath = websocketPath;
    }
}
