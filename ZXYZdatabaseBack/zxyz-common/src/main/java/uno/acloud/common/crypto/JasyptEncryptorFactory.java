package uno.acloud.common.crypto;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.iv.IvGenerator;
import org.jasypt.iv.RandomIvGenerator;

/**
 * {@link StandardPBEStringEncryptor} 的统一构建入口（自动配置 Bean 与配置解密钩子共用）。
 *
 * <p>共用一个构建点的原因：密文能否被解出来，取决于「算法 + IV 生成器 + 密钥」三者一致。
 * 若 Bean 与钩子各自构建，任何一处配置读法分叉（默认值、trim、类名大小写）都会表现为
 * 「同一个 ENC() 值，配置面解得开、工具面解不开」这类难查的缝。</p>
 */
public final class JasyptEncryptorFactory {

    private JasyptEncryptorFactory() {
    }

    /**
     * 按配置构建一个已初始化的加密器。
     *
     * @param properties 算法 / IV 生成器配置（{@code jasypt.encryptor.*}）
     * @param password   已通过 {@link JasyptPasswordPolicy#requireUsablePassword(String)} 校验的密钥
     * @return 可用的 {@link StandardPBEStringEncryptor}
     */
    public static StandardPBEStringEncryptor create(JasyptProperties properties, String password) {
        StandardPBEStringEncryptor encryptor = new StandardPBEStringEncryptor();
        encryptor.setAlgorithm(properties.getAlgorithm());
        encryptor.setIvGenerator(newIvGenerator(properties.getIvGeneratorClassname()));
        encryptor.setPassword(password);
        return encryptor;
    }

    private static IvGenerator newIvGenerator(String classname) {
        if (classname == null || classname.isBlank()) {
            return new RandomIvGenerator();
        }
        try {
            return Class.forName(classname.trim())
                    .asSubclass(IvGenerator.class)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Jasypt IV 生成器实例化失败：jasypt.encryptor.iv-generator-classname=" + classname
                            + "（必须是实现 org.jasypt.iv.IvGenerator 且有无参构造的类）", e);
        }
    }
}
