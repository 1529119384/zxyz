package uno.acloud.file;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.web.servlet.ServletComponentScan;

@ServletComponentScan
@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(basePackages = {"uno.acloud.file", "uno.acloud.common"})
public class ZxyzFileApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZxyzFileApplication.class, args);
    }
}
