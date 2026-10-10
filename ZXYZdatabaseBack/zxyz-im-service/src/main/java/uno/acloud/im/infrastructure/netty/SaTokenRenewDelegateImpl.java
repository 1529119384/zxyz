package uno.acloud.im.infrastructure.netty;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;
import uno.acloud.im.application.SaTokenRenewDelegate;

/**
 * {@link SaTokenRenewDelegate} 的 Sa-Token 实现（infrastructure 侧适配器）。
 *
 * <p>1.46.0 的 {@code StpUtil} 只提供<b>无参</b>静态转发（从当前请求上下文取 token，
 * Netty 业务线程上没有 SaTokenContext，不可用），而带参版本
 * {@code updateLastActiveToNow(String tokenValue)} 定义在 {@code StpLogic} 上——
 * 经 {@code StpUtil.stpLogic} 公开静态字段定位调用。</p>
 *
 * <p>独立成类的原因：把静态 StpUtil 调用收敛到唯一一处，使
 * {@link uno.acloud.im.application.ImSessionKeepAliveService} 的单测可 mock 委托。</p>
 */
@Component
public class SaTokenRenewDelegateImpl implements SaTokenRenewDelegate {

    @Override
    public void updateLastActiveToNow(String tokenValue) {
        StpUtil.stpLogic.updateLastActiveToNow(tokenValue);
    }
}
