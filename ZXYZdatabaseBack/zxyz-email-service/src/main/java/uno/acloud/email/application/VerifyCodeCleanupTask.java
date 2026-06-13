package uno.acloud.email.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.email.infrastructure.VerifyCodeMapper;

@Slf4j
@Component
public class VerifyCodeCleanupTask {

    private final VerifyCodeMapper verifyCodeMapper;

    public VerifyCodeCleanupTask(VerifyCodeMapper verifyCodeMapper) {
        this.verifyCodeMapper = verifyCodeMapper;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredCodes() {
        try {
            int deleted = verifyCodeMapper.deleteExpired();
            if (deleted > 0) {
                log.info("清理过期验证码完成，删除数量={}", deleted);
            }
        } catch (Exception e) {
            log.warn("清理过期验证码任务异常", e);
        }
    }
}
