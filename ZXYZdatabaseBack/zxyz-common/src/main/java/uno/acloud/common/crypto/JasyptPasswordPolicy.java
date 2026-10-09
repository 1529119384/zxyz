package uno.acloud.common.crypto;

/**
 * Jasypt 主密钥的 fail-closed 策略（风格对齐 {@code VerifyCodeHasher} 的弱值拒绝逻辑）。
 *
 * <p>判定口径：<b>缺失、空白、未解析的占位符（"${...}"）、仓库公开占位值，都等于没有密钥</b>。
 * 与其拿着公开值继续跑（配置面形同明文），不如拒绝启动并给出可操作的报错。</p>
 *
 * <p>为什么"${"要单独拦：application-common.yml 的 {@code password: ${JASYPT_PASSWORD}}
 * 在 Binder（非严格占位符解析）下，环境变量缺失时绑定结果是字面量 {@code "${JASYPT_PASSWORD}"}。
 * 不拦它就会把这个字面量当真密钥用——「看起来在加密，实则谁都推得出来」。</p>
 */
public final class JasyptPasswordPolicy {

    /** YAML 里主密钥的键名（application-common.yml / nacos 配置共用） */
    public static final String PASSWORD_PROPERTY = "jasypt.encryptor.password";

    JasyptPasswordPolicy() {
    }

    /**
     * 校验主密钥可用并返回 trim 后的密钥；不可用则抛带明确处置指引的 {@link IllegalStateException}。
     *
     * @param raw 绑定/环境读取到的原始密钥值（可能为 null、空白、"${...}" 字面量或公开占位值）
     * @return 可用的密钥（已 trim）
     */
    public static String requireUsablePassword(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "Jasypt 主密钥未配置：请设置环境变量 JASYPT_PASSWORD（application-common.yml 的 "
                            + PASSWORD_PROPERTY + " 引用它）。"
                            + "拒绝以「无密钥」方式运行配置加解密（那等同于配置明文）。");
        }
        String password = raw.trim();
        if (password.startsWith("${") && password.endsWith("}")) {
            throw new IllegalStateException(
                    "Jasypt 主密钥未解析：" + PASSWORD_PROPERTY + " 的值是占位符 " + password
                            + "，说明它引用的环境变量（默认 JASYPT_PASSWORD）没有注入。"
                            + "请在该服务的运行环境设置 JASYPT_PASSWORD 后重启。");
        }
        String reason = insecureReason(password);
        if (reason != null) {
            throw new IllegalStateException(reason);
        }
        return password;
    }

    /**
     * 判定是否为「仓库里公开写着」的弱值。
     *
     * @return 拒绝的理由；不是已知弱值则返回 {@code null}
     */
    private static String insecureReason(String password) {
        if (password.startsWith("CHANGE_ME_")) {
            return "Jasypt 主密钥仍是仓库模板里的公开占位值（" + password + "），它等于没有密钥。"
                    + "请设置真实的 JASYPT_PASSWORD（生成方式见 docs/jasypt-key-management.md）。";
        }
        return null;
    }
}
