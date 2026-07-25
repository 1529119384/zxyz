package uno.acloud.file.service;

import uno.acloud.file.dto.BatchConfirmUploadRequest;
import uno.acloud.file.vo.BatchUploadConfirmResultVO;
import uno.acloud.file.storage.UploadInfo;

public interface FileUploadPort {

    UploadInfo getUploadSign(String originalName);

    BatchUploadConfirmResultVO confirmUpload(BatchConfirmUploadRequest request, Long userId);

    /**
     * 直传上传（后端直接接收文件流，适用于本地存储等非预签名提供者）
     */
    UploadInfo directUpload(String originalName, java.io.InputStream inputStream,
                            String contentType, Long parentId, Long userId,
                            Long teamId, Integer spaceType, Long projectId, Long fileSize);
}
