package uno.acloud.im.application;

/**
 * Sa-Token 会话续签端口（六边形架构 Port，仿 {@link ImRealtimePushService} 的分层方式）。
 *
 * <p><b>为什么需要它</b>：{@code sa-token.active-timeout: 1800}（zxyz-common application-common.yml）
 * 只在「直接或间接调用 getLoginId()/getTokenSession()」时被动续签，而
 * {@code getLoginIdByToken(token)}（IM WS 握手鉴权所用）<b>不在</b>续签清单。
 * 用户在 IM 页纯聊天 30 分钟不发 HTTP 请求时 token 会被冻结（场景 -3），
 * 此后全站 HTTP 请求 401。本端口把「手动续签」能力从 Netty 侧引到 application 层，
 * 使 {@link ImSessionKeepAliveService} 可在纯 JVM 单测中 mock 验证。</p>
 *
 * <p>实现位于 infrastructure.netty（{@code SaTokenRenewDelegateImpl}），包装
 * {@code StpUtil.stpLogic.updateLastActiveToNow(tokenValue)}——该方法在
 * Sa-Token 1.46.0 的 {@code StpLogic} 上为 public（GitHub v1.46.0 源码 1711 行实证），
 * 而 {@code StpUtil} 只有无参静态转发（其无参重载从请求上下文取 token，
 * Netty 线程上无 SaTokenContext 不可用），故必须借 {@code stpLogic} 公开字段定位。</p>
 */
public interface SaTokenRenewDelegate {

    /**
     * 将指定 token 的「最后活跃时间」更新为当前时间戳。
     *
     * @param tokenValue Sa-Token 会话的 token 值
     */
    void updateLastActiveToNow(String tokenValue);
}
