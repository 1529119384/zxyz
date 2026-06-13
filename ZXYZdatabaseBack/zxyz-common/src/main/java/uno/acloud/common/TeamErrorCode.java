package uno.acloud.common;

import org.springframework.http.HttpStatus;

/**
 * 团队领域错误码。
 * 从 {@link ErrorCode} 拆分而来，保留相同数值以保证前后端契约不变。
 */
public enum TeamErrorCode implements ErrorCodeMarker {

    TEAM_NOT_FOUND(4400, HttpStatus.NOT_FOUND),
    TEAM_PERMISSION_DENIED(4401, HttpStatus.FORBIDDEN),
    TEAM_MEMBER_EXISTS(4402, HttpStatus.CONFLICT),
    TEAM_INVITATION_INVALID(4403, HttpStatus.GONE),
    TEAM_INVITATION_EXPIRED(4404, HttpStatus.GONE);

    private final int code;
    private final HttpStatus httpStatus;

    TeamErrorCode(int code, HttpStatus httpStatus) {
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
