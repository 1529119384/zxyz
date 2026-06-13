package uno.acloud.file.infrastructure.oss;

import uno.acloud.exception.BusinessException;
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.CopyObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.oss.OSSProperties;
import uno.acloud.common.util.oss.OssContentDispositionUtil;

@Slf4j
@Component
@RequiredArgsConstructor
public class OSSMetadataUpdater {

    private static final String METADATA_DIRECTIVE_REPLACE = "REPLACE";

    private final OSSClient ossClient;
    private final OSSProperties ossProperties;

    public void updateDownloadFileName(String objectKey, String originalName) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "更新 OSS 文件元数据失败：objectKey 不能为空");
        }
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "更新 OSS 文件元数据失败：originalName 不能为空");
        }

        String bucket = ossProperties.getBucket();
        try {
            HeadObjectResult sourceMetadata = ossClient.headObject(
                    HeadObjectRequest.newBuilder()
                            .bucket(bucket)
                            .key(objectKey)
                            .build()
            );

            CopyObjectRequest.Builder copyRequestBuilder = CopyObjectRequest.newBuilder()
                    .bucket(bucket)
                    .key(objectKey)
                    .sourceBucket(bucket)
                    .sourceKey(objectKey)
                    .metadataDirective(METADATA_DIRECTIVE_REPLACE)
                    .contentDisposition(OssContentDispositionUtil.buildAttachmentFileName(originalName));

            applyIfPresent(sourceMetadata.contentType(), copyRequestBuilder::contentType);
            applyIfPresent(sourceMetadata.contentEncoding(), copyRequestBuilder::contentEncoding);
            applyIfPresent(sourceMetadata.cacheControl(), copyRequestBuilder::cacheControl);
            applyIfPresent(sourceMetadata.expires(), copyRequestBuilder::expires);
            applyIfPresent(sourceMetadata.storageClass(), copyRequestBuilder::storageClass);
            applyIfPresent(sourceMetadata.serverSideEncryption(), copyRequestBuilder::serverSideEncryption);
            applyIfPresent(sourceMetadata.serverSideDataEncryption(), copyRequestBuilder::serverSideDataEncryption);
            applyIfPresent(sourceMetadata.serverSideEncryptionKeyId(), copyRequestBuilder::serverSideEncryptionKeyId);
            if (sourceMetadata.metadata() != null && !sourceMetadata.metadata().isEmpty()) {
                copyRequestBuilder.metadata(sourceMetadata.metadata());
            }

            CopyObjectRequest copyRequest = copyRequestBuilder.build();

            ossClient.copyObject(copyRequest);
            log.info("OSS 文件元数据更新成功，objectKey: {}, originalName: {}", objectKey, originalName);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("更新 OSS 文件元数据异常，objectKey: {}, originalName: {}", objectKey, originalName, e);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "更新 OSS 文件元数据失败：" + buildExceptionSummary(e));
        }
    }

    private void applyIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    private String buildExceptionSummary(Exception e) {
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
    }
}
