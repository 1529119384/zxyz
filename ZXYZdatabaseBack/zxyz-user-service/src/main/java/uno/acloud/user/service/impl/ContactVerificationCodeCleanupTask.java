package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.user.mapper.UserMapper;

@Slf4j
@Component
public class ContactVerificationCodeCleanupTask {

    private final UserMapper userMapper;

    public ContactVerificationCodeCleanupTask(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredCodes() {
        try {
            int deleted = userMapper.deleteExpiredContactVerificationCodes();
            if (deleted > 0) {
                log.info("清理过期联系验证码完成，删除数量={}", deleted);
            }
        } catch (Exception e) {
            log.warn("清理过期联系验证码任务异常", e);
        }
    }
}
