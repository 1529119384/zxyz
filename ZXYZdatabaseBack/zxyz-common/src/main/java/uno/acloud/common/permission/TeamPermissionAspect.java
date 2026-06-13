package uno.acloud.common.permission;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.lang.Nullable;

import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.satoken.AuthServicePort;

public abstract class TeamPermissionAspect {

    private final TeamPermissionPort teamPermissionPort;
    private final AuthServicePort authServicePort;

    protected TeamPermissionAspect(TeamPermissionPort teamPermissionPort, AuthServicePort authServicePort) {
        this.teamPermissionPort = teamPermissionPort;
        this.authServicePort = authServicePort;
    }

    @Around("@annotation(uno.acloud.common.permission.RequiresTeamPermission)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RequiresTeamPermission annotation = method.getAnnotation(RequiresTeamPermission.class);

        Long teamId = resolveTeamId(joinPoint, annotation.teamIdArg());
        if (teamId == null) {
            if (annotation.skipWhenTeamIdMissing()) {
                return joinPoint.proceed();
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "teamId 不能为空");
        }

        teamPermissionPort.check(authServicePort.getCurrentUserId(), teamId, annotation.value());
        return joinPoint.proceed();
    }

    @Nullable
    private Long resolveTeamId(ProceedingJoinPoint joinPoint, String argName) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        String[] parts = argName.split("\\.");
        String paramName = parts[0];

        int paramIndex = -1;
        for (int i = 0; i < paramNames.length; i++) {
            if (paramNames[i].equals(paramName)) {
                paramIndex = i;
                break;
            }
        }
        if (paramIndex == -1) {
            return null;
        }

        Object target = args[paramIndex];
        if (target == null) {
            return null;
        }

        if (parts.length > 1) {
            for (int i = 1; i < parts.length; i++) {
                target = readFieldValue(target, parts[i]);
                if (target == null) {
                    return null;
                }
            }
        }

        if (target instanceof Number) {
            return ((Number) target).longValue();
        }
        return null;
    }

    @Nullable
    private Object readFieldValue(Object target, String fieldName) {
        try {
            String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            Method getter = target.getClass().getMethod(getterName);
            return getter.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
