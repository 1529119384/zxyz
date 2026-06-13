package uno.acloud.im.config;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

public final class ImAuthContext {

    public static final String USER_ID_ATTRIBUTE = "IM_CURRENT_USER_ID";

    private ImAuthContext() {
    }

    public static Long currentUserId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object userId = attributes == null ? null : attributes.getAttribute(USER_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (userId instanceof Long value) {
            return value;
        }
        throw new BusinessException(ErrorCode.NO_LOGIN, "NO_LOGIN");
    }
}
