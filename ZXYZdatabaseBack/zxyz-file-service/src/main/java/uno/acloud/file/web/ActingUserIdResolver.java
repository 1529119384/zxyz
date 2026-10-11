package uno.acloud.file.web;

import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 内部链路操作者身份解析器（P1-1）。
 *
 * <p><b>背景（ISSUE/54 §2.7 P1-1）</b>：im→file 的 {@code /api/internal/im-file-cards/*}
 * 走内部 token 鉴权（{@code InternalServiceAuthInterceptor} 对 {@code /api/internal/**} 生效，
 * Sa-Token 对该路径排除），此链路上<b>不存在用户会话</b> —— 旧实现端点内调用
 * {@code authServicePort.getCurrentUserId()}（SaTokenAuthServicePort → StpUtil.getLoginIdAsLong()）
 * 必然抛 NotLoginException，IM 发文件卡片/解析卡片主路径自 6 月起 401 断裂。</p>
 *
 * <p><b>修法（Lead 已定方向）</b>：调用方（im-service）在发起内部调用时带上
 * {@code X-Acting-User-Id} 请求头（userId 在 im 侧的握手/HTTP 拦截器上下文里已可得）；
 * file 端内部端点从该头解析操作者身份。内部 token 鉴权已在过滤器/拦截器层完成，
 * 本类只负责「头存在且为合法 Long」的校验：缺失或非法时抛出明确的
 * {@link BusinessException}（BAD_REQUEST），绝不回退到 StpUtil —— 内部链路不走用户会话语义。</p>
 *
 * <p>为何不用 AuthServicePort：端口接口在 zxyz-common（本轮不许改），且其唯一实现绑定
 * Sa-Token 会话语义，与「无会话的内部链路」天然冲突。头解析只依赖
 * RequestContextHolder，file 端可独立完成、无需动 common。</p>
 */
@Component
public class ActingUserIdResolver {

    /** 内部链路操作者身份请求头（im→file 等内部调用必须携带）。 */
    public static final String ACTING_USER_ID_HEADER = "X-Acting-User-Id";

    /**
     * 从当前请求的 {@code X-Acting-User-Id} 头解析操作者用户 ID。
     *
     * <p>实现说明：Spring MVC 在进入 controller 前，header 已经由
     * {@code ServletRequestAttributes} 缓存进 request attributes（headerNames / headerValues），
     * 因此无需持有 HttpServletRequest 参数即可在 service 层读取。</p>
     *
     * @return 操作者用户 ID
     * @throws BusinessException 头缺失、空白或非合法 Long 时抛 BAD_REQUEST
     */
    public Long requireActingUserId() {
        String rawValue = currentHeader();
        if (rawValue == null || rawValue.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "内部链路缺少 " + ACTING_USER_ID_HEADER + " 请求头");
        }
        try {
            return Long.valueOf(rawValue.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    ACTING_USER_ID_HEADER + " 必须是合法的用户 ID");
        }
    }

    @Nullable
    private String currentHeader() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getHeader(ACTING_USER_ID_HEADER);
        }
        return null;
    }
}
