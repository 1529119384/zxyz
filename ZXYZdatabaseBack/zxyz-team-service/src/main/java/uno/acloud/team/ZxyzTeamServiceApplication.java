package uno.acloud.team;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

@ConfigurationPropertiesScan
@ComponentScan(basePackages = {"uno.acloud.team", "uno.acloud.common"})
@SpringBootApplication
public class ZxyzTeamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZxyzTeamServiceApplication.class, args);
    }
}
