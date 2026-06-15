package uno.acloud.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;

@ServletComponentScan
@SpringBootApplication
@EnableDiscoveryClient
@ComponentScan(basePackages = {"uno.acloud.admin", "uno.acloud.common"})
@ConfigurationPropertiesScan
public class ZxyzAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZxyzAdminApplication.class, args);
    }

}
