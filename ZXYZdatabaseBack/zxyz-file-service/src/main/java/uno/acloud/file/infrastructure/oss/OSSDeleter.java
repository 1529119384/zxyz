package uno.acloud.file.infrastructure.oss;

import uno.acloud.exception.BusinessException;
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.Delete;
import com.aliyun.sdk.service.oss2.models.DeleteMultipleObjectsRequest;
import com.aliyun.sdk.service.oss2.models.DeleteMultipleObjectsResult;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.DeleteObjectResult;
import com.aliyun.sdk.service.oss2.models.ObjectIdentifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.oss.OSSProperties;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class OSSDeleter {

    private final OSSProperties ossProperties;
    private final OSSClient ossClient;

    public void delete(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "删除失败：key 不能为空");
        }

        String bucket = ossProperties.getBucket();

        try {
            log.info("开始删除 OSS 文件，bucket: {}, key: {}", bucket, key);

            DeleteObjectResult result = ossClient.deleteObject(
                    DeleteObjectRequest.newBuilder()
                            .bucket(bucket)
                            .key(key)
                            .build()
            );

            if (result.statusCode() != 204 && result.statusCode() != 200) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "删除失败，状态码：" + result.statusCode());
            }

            log.info("删除成功，bucket: {}, key: {}, requestId: {}", bucket, key, result.requestId());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("删除 OSS 文件异常，key: {}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作失败，请稍后重试");
        }
    }

    public void deleteBatch(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "批量删除失败：keys 不能为空");
        }

        List<String> validKeys = keys.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());

        if (validKeys.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "批量删除失败：没有可用的 key");
        }

        String bucket = ossProperties.getBucket();

        try {
            log.info("开始批量删除 OSS 文件，bucket: {}, 数量: {}", bucket, validKeys.size());

            List<ObjectIdentifier> objects = validKeys.stream()
                    .map(key -> ObjectIdentifier.newBuilder().key(key).build())
                    .collect(Collectors.toList());

            Delete delete = Delete.newBuilder()
                    .quiet(false)
                    .objects(objects)
                    .build();

            DeleteMultipleObjectsResult result = ossClient.deleteMultipleObjects(
                    DeleteMultipleObjectsRequest.newBuilder()
                            .bucket(bucket)
                            .delete(delete)
                            .build()
            );

            if (result.statusCode() != 200) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "批量删除失败，状态码：" + result.statusCode());
            }

            log.info("批量删除成功，bucket: {}, 数量: {}, requestId: {}", bucket, validKeys.size(), result.requestId());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("批量删除 OSS 文件异常，keys: {}", validKeys, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作失败，请稍后重试");
        }
    }
}
