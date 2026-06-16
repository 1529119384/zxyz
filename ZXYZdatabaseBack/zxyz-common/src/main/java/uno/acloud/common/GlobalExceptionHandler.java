package uno.acloud.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import uno.acloud.exception.BusinessException;

import java.util.List;
import java.util.Set;

@RestControllerAdvice(basePackages = {"uno.acloud.user", "uno.acloud.team", "uno.acloud.project", "uno.acloud.file", "uno.acloud.share", "uno.acloud.email", "uno.acloud.audit", "uno.acloud.gateway", "uno.acloud.im", "uno.acloud.admin"})
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常：{}", e.getMessage());
        if (e.getData() != null) {
            return ResponseEntity.status(ErrorCode.resolveHttpStatus(e.getErrorCode()))
                    .body(Result.error(e.getErrorCode(), e.getMessage(), e.getData()));
        }
        return ResponseEntity.status(ErrorCode.resolveHttpStatus(e.getErrorCode()))
                .body(Result.error(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLoginException(NotLoginException e) {
        log.warn("未登录访问: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(ErrorCode.NO_LOGIN, "未登录或登录已过期"));
    }

    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<Result<Void>> handleNotPermissionException(NotPermissionException e) {
        log.warn("无权限访问: permission={}, message={}", e.getPermission(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error(ErrorCode.NO_PERMISSION, "无操作权限: " + e.getPermission()));
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<Result<Void>> handleNotRoleException(NotRoleException e) {
        log.warn("无角色访问: role={}, message={}", e.getRole(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error(ErrorCode.NO_PERMISSION, "无角色权限: " + e.getRole()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ErrorCode.SYSTEM_ERROR, "操作失败，请稍后重试"));
    }

    /**
     * @Valid / @Validated 请求体参数校验失败
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        log.warn("参数校验异常：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, extractBindingMessage(e.getBindingResult())));
    }

    /**
     * @RequestParam 缺少必填参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        String message = "缺少参数：" + e.getParameterName();
        log.warn("缺少请求参数：{}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * 请求体无法反序列化（JSON 格式错误等）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体格式错误：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, "请求体格式错误"));
    }

    /**
     * @Valid 表单绑定校验失败（如 @ModelAttribute 绑定的表单对象）
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        log.warn("绑定校验异常：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, extractBindingMessage(e.getBindingResult())));
    }

    /**
     * @Validated 方法级别参数校验失败（如 @PathVariable、@RequestParam 上的约束）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("约束校验异常：{}", e.getMessage());
        Set<ConstraintViolation<?>> violations = e.getConstraintViolations();
        String message = violations.stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * 从 BindingResult 中提取校验错误消息。
     * 优先取字段级错误，其次取全局错误，兜底返回"参数校验失败"。
     */
    private static String extractBindingMessage(org.springframework.validation.BindingResult bindingResult) {
        // 优先字段级错误
        List<FieldError> fieldErrors = bindingResult.getFieldErrors();
        if (!fieldErrors.isEmpty()) {
            FieldError first = fieldErrors.get(0);
            String msg = first.getDefaultMessage();
            return (msg != null && !msg.isBlank()) ? msg : "参数校验失败";
        }
        // 其次全局错误
        List<ObjectError> globalErrors = bindingResult.getGlobalErrors();
        if (!globalErrors.isEmpty()) {
            ObjectError first = globalErrors.get(0);
            String msg = first.getDefaultMessage();
            return (msg != null && !msg.isBlank()) ? msg : "参数校验失败";
        }
        return "参数校验失败";
    }
}
