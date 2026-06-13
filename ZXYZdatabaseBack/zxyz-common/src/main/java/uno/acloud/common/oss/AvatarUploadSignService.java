package uno.acloud.common.oss;

import com.aliyun.sdk.service.oss2.OSSClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.util.FileNameUtil;
import uno.acloud.common.util.oss.OssContentDispositionUtil;
import uno.acloud.exception.BusinessException;

import java.util.Map;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

@Service
@ConditionalOnClass(OSSClient.class)
public class AvatarUploadSignService {

    private static final String AVATAR_OBJECT_PREFIX = "avatar/";
    private static final long MAX_AVATAR_SIZE = 5L * 1024L * 1024L;
    private static final int MAX_AVATAR_URL_LENGTH = 512;
    private static final Map<String, String> ALLOWED_TYPES_BY_EXTENSION = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp"
    );
    private static final Map<String, String> NORMALIZED_CONTENT_TYPES = Map.of(
            "image/jpg", "image/jpeg",
            "image/jpeg", "image/jpeg",
            "image/png", "image/png",
            "image/webp", "image/webp"
    );

    private final GetSignUrl getSignUrl;
    private final OSSProperties ossProperties;

    public AvatarUploadSignService(GetSignUrl getSignUrl, OSSProperties ossProperties) {
        this.getSignUrl = getSignUrl;
        this.ossProperties = ossProperties;
    }

    public OssSignInfo generateAvatarUploadSign(AvatarUploadSignRequest request) {
        String fileName = requireText(request == null ? null : request.getFileName(), "头像文件名不能为空");
        validateAvatarSize(request.getFileSize());
        String extension = resolveExtension(fileName);
        String signedContentType = resolveContentType(extension, request.getContentType());
        String objectKey = AVATAR_OBJECT_PREFIX + FileNameUtil.uuidName(fileName);
        return getSignUrl.generatePutSignInfo(
                objectKey,
                fileName,
                signedContentType,
                OssContentDispositionUtil.buildInlineFileName(fileName)
        );
    }

    public String normalizeManagedAvatarUrl(String avatar, String maxLengthMessage) {
        String normalized = optionalText(avatar, MAX_AVATAR_URL_LENGTH, maxLengthMessage);
        if (normalized != null && !isManagedAvatarUrl(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像地址必须来自头像上传接口");
        }
        return normalized;
    }

    public boolean isManagedAvatarUrl(String avatarUrl) {
        String normalized = optionalText(avatarUrl);
        return normalized != null && normalized.startsWith(buildAvatarUrlPrefix());
    }

    private void validateAvatarSize(Long fileSize) {
        if (fileSize == null || fileSize <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件大小非法");
        }
        if (fileSize > MAX_AVATAR_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件不能超过 5MB");
        }
    }

    private String resolveExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String extension = dotIndex >= 0 && dotIndex < fileName.length() - 1
                ? fileName.substring(dotIndex + 1).toLowerCase()
                : "";
        if (!ALLOWED_TYPES_BY_EXTENSION.containsKey(extension)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像只支持 jpg、png、webp 格式");
        }
        return extension;
    }

    private String resolveContentType(String extension, String contentType) {
        String expectedContentType = ALLOWED_TYPES_BY_EXTENSION.get(extension);
        String providedContentType = optionalText(contentType);
        if (providedContentType == null) {
            return expectedContentType;
        }
        String normalizedContentType = NORMALIZED_CONTENT_TYPES.get(providedContentType.toLowerCase());
        if (normalizedContentType == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像只支持 jpg、png、webp 格式");
        }
        if (!expectedContentType.equals(normalizedContentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件扩展名与类型不一致");
        }
        return normalizedContentType;
    }

    private String buildAvatarUrlPrefix() {
        if (ossProperties.getPublicBaseUrl() != null && !ossProperties.getPublicBaseUrl().isBlank()) {
            return trimTrailingSlash(ossProperties.getPublicBaseUrl()) + "/" + AVATAR_OBJECT_PREFIX;
        }
        return "https://" + ossProperties.getBucket() + ".oss-" + ossProperties.getRegion() + ".aliyuncs.com/" + AVATAR_OBJECT_PREFIX;
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
