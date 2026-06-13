package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.file.config.ServiceProperties;

@Slf4j
@Component
public class FileObjectDeleteRetryTask {

    private final FileObjectPhysicalDeleteExecutor fileObjectPhysicalDeleteService;
    private final boolean enabled;
    private final int batchSize;

    public FileObjectDeleteRetryTask(FileObjectPhysicalDeleteExecutor fileObjectPhysicalDeleteService,
                                     ServiceProperties serviceProperties) {
        this.fileObjectPhysicalDeleteService = fileObjectPhysicalDeleteService;
        this.enabled = serviceProperties.getFileObjectDelete().isEnabled();
        this.batchSize = serviceProperties.getFileObjectDelete().getBatchSize();
    }

    @Scheduled(
            initialDelayString = "${app.file-object-delete.initial-delay-ms:30000}",
            fixedDelayString = "${app.file-object-delete.retry-fixed-delay-ms:60000}"
    )
    public void retryPendingDeletes() {
        if (!enabled) {
            log.warn("OSS 文件物理删除任务已禁用（app.file-object-delete.enabled=false）");
            return;
        }
        try {
            int successCount = fileObjectPhysicalDeleteService.deletePendingObjects(batchSize);
            if (successCount > 0) {
                log.info("OSS 待删除对象重试完成，成功数量={}", successCount);
            } else {
                log.debug("OSS 待删除对象重试完成，无成功删除");
            }
        } catch (Exception e) {
            log.warn("OSS 待删除对象重试任务异常", e);
        }
    }
}
