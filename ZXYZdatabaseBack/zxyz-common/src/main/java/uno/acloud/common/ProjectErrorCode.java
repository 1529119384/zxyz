package uno.acloud.common;

import org.springframework.http.HttpStatus;

/**
 * 项目领域错误码。
 * 从 {@link ErrorCode} 拆分而来，保留相同数值以保证前后端契约不变。
 */
public enum ProjectErrorCode implements ErrorCodeMarker {

    PROJECT_NOT_FOUND(4410, HttpStatus.NOT_FOUND),
    PROJECT_CREATE_REQUEST_NOT_FOUND(4411, HttpStatus.NOT_FOUND);

    private final int code;
    private final HttpStatus httpStatus;

    ProjectErrorCode(int code, HttpStatus httpStatus) {
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
