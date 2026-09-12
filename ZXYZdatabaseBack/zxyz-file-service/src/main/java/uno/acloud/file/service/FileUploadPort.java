package uno.acloud.file.service;

import uno.acloud.file.dto.BatchConfirmUploadRequest;
import uno.acloud.file.vo.BatchUploadConfirmResultVO;
import uno.acloud.file.storage.UploadInfo;

public interface FileUploadPort {

    /**
     * 生成预签名上传信息，并登记「objectKey → userId」归属凭证（审计 12-2.1.2）。
     *
     * <p>objectKey 会随 fileUrl 对外暴露，若不在发签名时就留下归属，
     * 知道 objectKey 的人就能把它挂进自己的空间。confirm 阶段据此强制校验。</p>
     *
     * @param originalName 原始文件名
     * @param userId       发起签名的用户 id
     * @return 上传信息（含 objectKey / 预签名 URL / 过期时间）
     */
    UploadInfo getUploadSign(String originalName, Long userId);

    BatchUploadConfirmResultVO confirmUpload(BatchConfirmUploadRequest request, Long userId);

    /**
     * 直传上传（后端直接接收文件流，适用于本地存储等非预签名提供者）
     */
    UploadInfo directUpload(String originalName, java.io.InputStream inputStream,
                            String contentType, Long parentId, Long userId,
                            Long teamId, Integer spaceType, Long projectId, Long fileSize);
}
