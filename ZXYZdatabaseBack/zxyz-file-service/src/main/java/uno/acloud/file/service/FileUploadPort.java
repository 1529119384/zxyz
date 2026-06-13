package uno.acloud.file.service;

import uno.acloud.file.dto.BatchConfirmUploadRequest;
import uno.acloud.file.vo.BatchUploadConfirmResultVO;
import uno.acloud.common.oss.OssSignInfo;

public interface FileUploadPort {

    OssSignInfo getUploadSign(String originalName);

    BatchUploadConfirmResultVO confirmUpload(BatchConfirmUploadRequest request, Long userId);
}
