package uno.acloud.im;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"uno.acloud.im", "uno.acloud.common"})
@ConfigurationPropertiesScan
@MapperScan("uno.acloud.im.infrastructure.mapper")
public class ZxyzImApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZxyzImApplication.class, args);
    }
}
