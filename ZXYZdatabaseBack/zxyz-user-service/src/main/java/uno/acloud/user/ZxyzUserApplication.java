package uno.acloud.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(basePackages = {"uno.acloud.user", "uno.acloud.common"})
public class ZxyzUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZxyzUserApplication.class, args);
    }
}
