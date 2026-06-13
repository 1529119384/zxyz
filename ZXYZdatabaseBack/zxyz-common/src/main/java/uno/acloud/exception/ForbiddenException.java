package uno.acloud.exception;

import uno.acloud.common.ErrorCode;

/**
 * 权限不足异常。
 * <p>自动使用 {@link ErrorCode#NO_PERMISSION} 错误码，调用方也可传入更具体的错误码（如 TEAM_PERMISSION_DENIED）。</p>
 */
public class ForbiddenException extends BusinessException {

    /**
     * 使用默认 NO_PERMISSION 错误码。
     */
    public ForbiddenException(String message) {
        super(ErrorCode.NO_PERMISSION, message);
    }

    /**
     * 使用指定错误码（如 TEAM_PERMISSION_DENIED）。
     */
    public ForbiddenException(int errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * 使用指定错误码并携带附加数据。
     */
    public ForbiddenException(int errorCode, String message, Object data) {
        super(errorCode, message, data);
    }
}
