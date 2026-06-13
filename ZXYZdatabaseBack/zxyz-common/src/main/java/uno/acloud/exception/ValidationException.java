package uno.acloud.exception;

import uno.acloud.common.ErrorCode;

/**
 * 输入校验异常。
 * <p>自动使用 {@link ErrorCode#BAD_REQUEST} 错误码，调用方也可传入更具体的错误码。</p>
 */
public class ValidationException extends BusinessException {

    /**
     * 使用默认 BAD_REQUEST 错误码。
     */
    public ValidationException(String message) {
        super(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 使用指定错误码。
     */
    public ValidationException(int errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * 使用指定错误码并携带附加数据。
     */
    public ValidationException(int errorCode, String message, Object data) {
        super(errorCode, message, data);
    }
}
