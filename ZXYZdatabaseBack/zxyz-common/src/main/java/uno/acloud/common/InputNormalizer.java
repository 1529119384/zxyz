package uno.acloud.common;

import org.springframework.lang.Nullable;
import uno.acloud.exception.BusinessException;

/**
 * 统一处理外部输入文本，避免各业务服务重复维护 trim、空值和 BAD_REQUEST 语义。
 */
public final class InputNormalizer {

    private InputNormalizer() {
    }

    @Nullable
    public static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static String requireText(String value, String message) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return normalized;
    }

    @Nullable
    public static String optionalText(String value, int maxLength, String message) {
        String normalized = optionalText(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return normalized;
    }

    public static String requireText(String value, String emptyMessage, int maxLength, String maxLengthMessage) {
        String normalized = requireText(value, emptyMessage);
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, maxLengthMessage);
        }
        return normalized;
    }
}
