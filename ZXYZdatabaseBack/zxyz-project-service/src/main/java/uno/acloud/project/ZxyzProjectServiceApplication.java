package uno.acloud.project;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.ComponentScan;

@ServletComponentScan
@SpringBootApplication
@ComponentScan(basePackages = {"uno.acloud.project", "uno.acloud.common"})
@ConfigurationPropertiesScan
public class ZxyzProjectServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZxyzProjectServiceApplication.class, args);
    }

}
