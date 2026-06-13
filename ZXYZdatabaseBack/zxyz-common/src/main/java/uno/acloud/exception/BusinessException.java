package uno.acloud.exception;

import lombok.Getter;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.ErrorCodeMarker;

/**
 * 统一业务异常基类，直接携带稳定错误码，避免业务码依赖中文文案。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int errorCode;
    private final Object data;

    public BusinessException(ErrorCodeMarker errorCode, String message) {
        this(errorCode.getCode(), message, null);
    }

    public BusinessException(ErrorCodeMarker errorCode, String message, Object data) {
        this(errorCode.getCode(), message, data);
    }

    public BusinessException(int errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(int errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    /**
     * 根据业务错误码返回对应的 HTTP 状态码。
     * 委托给 {@link ErrorCode#resolveHttpStatus(int)}，确保映射规则集中维护。
     */
    public int getHttpStatus() {
        return ErrorCode.resolveHttpStatus(errorCode).value();
    }

}
