package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;

@Slf4j
@Component
public class FileObjectRefCleanupTask {

    private final FileObjectRefMapper fileObjectRefMapper;

    public FileObjectRefCleanupTask(FileObjectRefMapper fileObjectRefMapper) {
        this.fileObjectRefMapper = fileObjectRefMapper;
    }

    /**
     * 每日清理任务：
     * <p>
     * 清理超过 30 天的 DELETED 状态 ref 记录（兜底用）。
     * </p>
     */
    @Scheduled(fixedRate = 86400000)
    public void cleanupExpiredDeletedRefs() {
        try {
            int deletedCount = fileObjectRefMapper.deleteExpiredDeleted();
            if (deletedCount > 0) {
                log.info("清理过期 DELETED 状态 file_object_ref 记录完成，删除数量={}", deletedCount);
            } else {
                log.debug("清理过期 DELETED 状态 file_object_ref 记录完成，无需清理");
            }
        } catch (Exception e) {
            log.warn("清理过期 DELETED 状态 file_object_ref 记录任务异常", e);
        }
    }
}
