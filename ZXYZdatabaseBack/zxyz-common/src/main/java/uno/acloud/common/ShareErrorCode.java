package uno.acloud.common;

import org.springframework.http.HttpStatus;

/**
 * 分享领域错误码。
 * 从 {@link ErrorCode} 拆分而来，保留相同数值以保证前后端契约不变。
 */
public enum ShareErrorCode implements ErrorCodeMarker {

    SHARE_NOT_FOUND(4300, HttpStatus.NOT_FOUND),
    SHARE_EXPIRED(4301, HttpStatus.FORBIDDEN),
    SHARE_PASSWORD_INVALID(4302, HttpStatus.FORBIDDEN),
    SHARE_FILE_OUT_OF_SCOPE(4303, HttpStatus.FORBIDDEN),
    SHARE_STATUS_INVALID(4304, HttpStatus.FORBIDDEN);

    private final int code;
    private final HttpStatus httpStatus;

    ShareErrorCode(int code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    @Override
    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
