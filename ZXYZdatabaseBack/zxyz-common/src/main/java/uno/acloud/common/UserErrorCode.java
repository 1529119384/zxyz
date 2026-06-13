package uno.acloud.common;

import org.springframework.http.HttpStatus;

/**
 * 用户领域错误码。
 * 从 {@link ErrorCode} 拆分而来，保留相同数值以保证前后端契约不变。
 */
public enum UserErrorCode implements ErrorCodeMarker {

    USER_NOT_FOUND(4001, HttpStatus.NOT_FOUND),
    LOGIN_FAILED(4100, HttpStatus.UNAUTHORIZED),
    USERNAME_EXISTS(4101, HttpStatus.CONFLICT);

    private final int code;
    private final HttpStatus httpStatus;

    UserErrorCode(int code, HttpStatus httpStatus) {
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
