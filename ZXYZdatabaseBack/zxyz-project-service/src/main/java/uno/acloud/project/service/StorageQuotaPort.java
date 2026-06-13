package uno.acloud.project.service;

import uno.acloud.project.vo.StorageUsageVO;

/**
 * 存储配额应用服务接口，供控制器与上传流程依赖。
 */
public interface StorageQuotaPort {

    StorageUsageVO getUsage(Long userId, Integer spaceType, Long teamId, Long projectId);

    long sumUsedStorage(Long userId, Long teamId, Integer spaceType, Long projectId);

    void checkUploadQuota(Long userId, Long teamId, Integer spaceType, Long projectId, long uploadBytes);
}
