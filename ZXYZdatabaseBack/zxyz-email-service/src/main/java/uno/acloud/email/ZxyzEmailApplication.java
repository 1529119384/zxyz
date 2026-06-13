package uno.acloud.email;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = {"uno.acloud.email", "uno.acloud.common"})
@ConfigurationPropertiesScan
@EnableScheduling
@MapperScan("uno.acloud.email.infrastructure")
public class ZxyzEmailApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZxyzEmailApplication.class, args);
    }
}
