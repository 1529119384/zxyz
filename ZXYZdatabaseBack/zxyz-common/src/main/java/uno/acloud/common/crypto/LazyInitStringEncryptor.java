package uno.acloud.common.crypto;

import org.jasypt.encryption.StringEncryptor;

/**
 * 首次使用时才构建真实加密器的 {@link StringEncryptor} 委托。
 *
 * <p><b>为什么必须惰性</b>：主密钥校验是 fail-closed 的（缺失/占位符/公开弱值直接抛异常）。
 * 若 Bean 创建时就校验，所有没有 {@code JASYPT_PASSWORD} 的上下文（各服务的集成测试、
 * 不消费加密配置的服务）都会在启动时炸——而它们本来就不需要解密任何值。
 * 原 jasypt starter 对 encryptor Bean 正是 lazy 初始化语义（只有真正发生 ENC() 解密时
 * 才会因缺密钥失败），本类把该语义原样保留。</p>
 *
 * <p>线程安全：双重检查 + {@code synchronized}，加密器构建是幂等的，重复构建无副作用。</p>
 */
public final class LazyInitStringEncryptor implements StringEncryptor {

    private final Object lock = new Object();

    private volatile StringEncryptor delegate;

    private final java.util.function.Supplier<StringEncryptor> factory;

    private LazyInitStringEncryptor(java.util.function.Supplier<StringEncryptor> factory) {
        this.factory = factory;
    }

    /**
     * @param factory 首次使用时执行的加密器构建逻辑（内部应完成密钥 fail-closed 校验）
     */
    public static LazyInitStringEncryptor of(java.util.function.Supplier<StringEncryptor> factory) {
        return new LazyInitStringEncryptor(factory);
    }

    private StringEncryptor delegate() {
        StringEncryptor result = delegate;
        if (result == null) {
            synchronized (lock) {
                result = delegate;
                if (result == null) {
                    result = factory.get();
                    delegate = result;
                }
            }
        }
        return result;
    }

    @Override
    public String encrypt(String message) {
        return delegate().encrypt(message);
    }

    @Override
    public String decrypt(String encryptedMessage) {
        return delegate().decrypt(encryptedMessage);
    }
}
