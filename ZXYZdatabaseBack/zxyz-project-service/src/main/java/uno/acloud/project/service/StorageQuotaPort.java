package uno.acloud.project.service;

import uno.acloud.project.vo.StorageUsageVO;

/**
 * 存储配额应用服务接口，供控制器与上传流程依赖。
 */
public interface StorageQuotaPort {

    StorageUsageVO getUsage(Long userId, Integer spaceType, Long teamId, Long projectId);

    long sumUsedStorage(Long userId, Long teamId, Integer spaceType, Long projectId);

    /**
     * 检查上传配额（超限即抛 {@link BusinessException}），并返回该作用域的有效存储上限（null=不限制）。
     * <p>返回的上限供 file-service 预检阶段写入配额台账，作为 confirm 原子扣减的守卫值。
     */
    Long checkUploadQuota(Long userId, Long teamId, Integer spaceType, Long projectId, long uploadBytes);
}
