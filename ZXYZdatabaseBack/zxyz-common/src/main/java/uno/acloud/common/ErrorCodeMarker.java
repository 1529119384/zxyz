package uno.acloud.common;

/**
 * 领域错误码标记接口。
 * 各领域的错误码枚举（如 {@code TeamErrorCode}、{@code ShareErrorCode}）实现此接口，
 * 使 {@link uno.acloud.exception.BusinessException} 能以类型安全方式接受枚举参数。
 */
public interface ErrorCodeMarker {

    /** 返回整数错误码 */
    int getCode();
}
