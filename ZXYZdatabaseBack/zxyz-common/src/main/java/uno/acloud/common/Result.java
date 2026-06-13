package uno.acloud.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class Result<T> {

    private Integer code;

    @JsonProperty("msg")
    private String msg;

    private T data;

    // --- 类型安全工厂方法 ---

    public static <T> Result<T> of(T data) {
        // 开发期检查，避免误用 Result.of(null)
        return new Result<>(ErrorCode.SUCCESS, "success", data);
    }

    // --- 向后兼容工厂方法 ---

    @Deprecated
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS, "success", data);
    }

    public static Result<Void> success() {
        return new Result<>(ErrorCode.SUCCESS, "success", null);
    }

    public static Result<Void> error(Integer code, String message) {
        // 错误响应必须透传调用方传入的业务错误码，保证前后端契约一致。
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> error(Integer code, String message, T data) {
        return new Result<>(code, message, data);
    }
}
