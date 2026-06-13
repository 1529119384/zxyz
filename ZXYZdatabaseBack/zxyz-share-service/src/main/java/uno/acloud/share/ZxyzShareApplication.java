package uno.acloud.share;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.ComponentScan;
import uno.acloud.share.config.ShareProperties;
import uno.acloud.share.config.ShareServiceProperties;
import uno.acloud.share.config.TeamServiceProperties;

@ServletComponentScan
@SpringBootApplication
// 显式扫描 common 包，确保 GlobalExceptionHandler 等全局组件被纳入
@ComponentScan(basePackages = {"uno.acloud.share", "uno.acloud.common"})
@EnableConfigurationProperties({TeamServiceProperties.class, ShareProperties.class, ShareServiceProperties.class})
public class ZxyzShareApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZxyzShareApplication.class, args);
    }
}
