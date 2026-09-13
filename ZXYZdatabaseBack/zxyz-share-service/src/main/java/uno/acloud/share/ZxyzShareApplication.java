package uno.acloud.share;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import uno.acloud.share.config.ShareProperties;
import uno.acloud.share.config.ShareServiceProperties;
import uno.acloud.share.config.TeamServiceProperties;

@ServletComponentScan
@SpringBootApplication
// 显式扫描 common 包，确保 GlobalExceptionHandler 等全局组件被纳入
@ComponentScan(basePackages = {"uno.acloud.share", "uno.acloud.common"})
// 分享条目对账任务（ShareItemReconcileTask）依赖 @Scheduled。本服务原先没有开启调度，
// 所以任何 @Scheduled 都会静默不执行 —— 这正是审计里反复出现的「声明了但没跑」形态。
// 注意：@EnableScheduling 缺省用单线程调度器，多任务会相互阻塞；common 已把池大小
// 配为 spring.task.scheduling.pool.size（默认 8），本服务当前只有这一个定时任务。
@EnableScheduling
@EnableConfigurationProperties({TeamServiceProperties.class, ShareProperties.class, ShareServiceProperties.class})
public class ZxyzShareApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZxyzShareApplication.class, args);
    }
}
