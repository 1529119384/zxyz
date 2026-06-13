package uno.acloud.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"uno.acloud.audit", "uno.acloud.common"})
public class ZxyzAuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZxyzAuditServiceApplication.class, args);
    }
}
