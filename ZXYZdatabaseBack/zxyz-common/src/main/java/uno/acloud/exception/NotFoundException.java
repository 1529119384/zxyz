package uno.acloud.exception;

import uno.acloud.common.ErrorCode;

/**
 * 资源未找到异常。
 * <p>自动使用 {@link ErrorCode#NOT_FOUND} 错误码，调用方也可传入更具体的错误码（如 TEAM_NOT_FOUND）。</p>
 */
public class NotFoundException extends BusinessException {

    /**
     * 使用默认 NOT_FOUND 错误码。
     */
    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    /**
     * 使用指定错误码（如 TEAM_NOT_FOUND、USER_NOT_FOUND）。
     */
    public NotFoundException(int errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * 使用指定错误码并携带附加数据。
     */
    public NotFoundException(int errorCode, String message, Object data) {
        super(errorCode, message, data);
    }
}
