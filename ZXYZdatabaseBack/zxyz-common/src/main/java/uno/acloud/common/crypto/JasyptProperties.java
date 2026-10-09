package uno.acloud.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jasypt 加密器配置（沿用原 jasypt-spring-boot-starter 的 {@code jasypt.encryptor.*} 键名）。
 *
 * <p><b>为什么由我们自建而不是继续用 starter</b>：jasypt-spring-boot-starter 自 2024-01 停更，
 * 其自动装配壳绑定旧版 spring-cloud-context，Boot 4（ISSUE/48 §九）下不可用；
 * 而内核 {@code org.jasypt:jasypt} 是零 Spring 依赖的纯 JCE 库，长期可用。
 * 本类只承接配置面，加密器构建见 {@link JasyptEncryptorFactory}。</p>
 *
 * <p><b>password 的绑定语义（重要）</b>：application-common.yml 写的是
 * {@code password: ${JASYPT_PASSWORD}}。Boot 的 Binder 对占位符解析是<b>非严格</b>的——
 * 环境变量缺失时绑定结果是字面量 {@code "${JASYPT_PASSWORD}"} 而不是启动失败
 * （这正是原 starter 在无密钥的测试上下文里不炸的原因）。因此密钥的缺失/占位/弱值
 * 一律不在绑定期拦截，而是延迟到<b>真正使用加密器时</b>由
 * {@link JasyptPasswordPolicy#requireUsablePassword(String)} fail-closed。</p>
 */
@ConfigurationProperties(prefix = "jasypt.encryptor")
public class JasyptProperties {

    /** PBE 算法名（必须是 SecretKeyFactory 认识的 PBE 算法，不是 Cipher 转换名——见 application-common.yml 注释） */
    private String algorithm = "PBEWITHHMACSHA512ANDAES_256";

    /** IV 生成器类名；空则用 {@link org.jasypt.iv.RandomIvGenerator} */
    private String ivGeneratorClassname = "org.jasypt.iv.RandomIvGenerator";

    /** 主密钥（生产经 {@code JASYPT_PASSWORD} 环境变量注入） */
    private String password;

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getIvGeneratorClassname() {
        return ivGeneratorClassname;
    }

    public void setIvGeneratorClassname(String ivGeneratorClassname) {
        this.ivGeneratorClassname = ivGeneratorClassname;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
