package uno.acloud.im.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;
import uno.acloud.common.util.LogSanitizer;

/**
 * IM 服务特有的异常处理器。
 * <p>通用异常（BusinessException、Sa-Token 登录/权限、参数校验、兜底）已由
 * {@code uno.acloud.common.GlobalExceptionHandler} 统一处理，
 * 此处仅补充请求参数类型不匹配。</p>
 */
@Slf4j
@Order(-1)
@RestControllerAdvice(basePackages = "uno.acloud.im")
public class ImExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("IM 请求参数格式错误：{}={}", LogSanitizer.sanitize(e.getName()), LogSanitizer.sanitize(String.valueOf(e.getValue())));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, "请求参数格式错误"));
    }

}
