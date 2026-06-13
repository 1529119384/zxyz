package uno.acloud.common;

import org.springframework.http.HttpStatus;

public final class ErrorCode {

    // ===== 通用 =====
    public static final int SUCCESS = 1;
    public static final int BAD_REQUEST = 4000;
    public static final int NO_LOGIN = 4010;
    public static final int NO_PERMISSION = 4030;
    public static final int NOT_FOUND = 4040;
    public static final int FILE_STATE_INVALID = 4090;
    public static final int CONCURRENT_OPERATION = 4091;
    public static final int SYSTEM_NOTIFICATION_NOT_FOUND = 4500;
    public static final int SEARCH_KEYWORD_EMPTY = 4600;
    public static final int SYSTEM_ERROR = 5000;

    // ===== 用户领域（建议新代码使用 {@link UserErrorCode} 枚举） =====
    public static final int USER_NOT_FOUND = 4001;
    public static final int LOGIN_FAILED = 4100;
    public static final int USERNAME_EXISTS = 4101;

    // ===== 分享领域（建议新代码使用 {@link ShareErrorCode} 枚举） =====
    public static final int SHARE_NOT_FOUND = 4300;
    public static final int SHARE_EXPIRED = 4301;
    public static final int SHARE_PASSWORD_INVALID = 4302;
    public static final int SHARE_FILE_OUT_OF_SCOPE = 4303;
    public static final int SHARE_STATUS_INVALID = 4304;

    // ===== 团队领域（建议新代码使用 {@link TeamErrorCode} 枚举） =====
    public static final int TEAM_NOT_FOUND = 4400;
    public static final int TEAM_PERMISSION_DENIED = 4401;
    public static final int TEAM_MEMBER_EXISTS = 4402;
    public static final int TEAM_INVITATION_INVALID = 4403;
    public static final int TEAM_INVITATION_EXPIRED = 4404;

    // ===== 项目领域（建议新代码使用 {@link ProjectErrorCode} 枚举） =====
    public static final int PROJECT_NOT_FOUND = 4410;
    public static final int PROJECT_CREATE_REQUEST_NOT_FOUND = 4411;

    /**
     * 根据业务错误码映射 HTTP 状态码。
     * 各服务的 GlobalExceptionHandler 应调用此方法，避免重复维护。
     */
    public static HttpStatus resolveHttpStatus(int errorCode) {
        return switch (errorCode) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case NO_LOGIN, LOGIN_FAILED -> HttpStatus.UNAUTHORIZED;
            case NO_PERMISSION,
                    TEAM_PERMISSION_DENIED,
                    SHARE_EXPIRED,
                    SHARE_PASSWORD_INVALID,
                    SHARE_FILE_OUT_OF_SCOPE,
                    SHARE_STATUS_INVALID -> HttpStatus.FORBIDDEN;
            case NOT_FOUND,
                    USER_NOT_FOUND,
                    SHARE_NOT_FOUND,
                    TEAM_NOT_FOUND,
                    PROJECT_NOT_FOUND,
                    PROJECT_CREATE_REQUEST_NOT_FOUND,
                    SYSTEM_NOTIFICATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FILE_STATE_INVALID,
                    USERNAME_EXISTS,
                    TEAM_MEMBER_EXISTS,
                    CONCURRENT_OPERATION -> HttpStatus.CONFLICT;
            case TEAM_INVITATION_INVALID,
                    TEAM_INVITATION_EXPIRED -> HttpStatus.GONE;
            case SEARCH_KEYWORD_EMPTY -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private ErrorCode() {
    }
}
