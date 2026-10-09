package uno.acloud.common.crypto;

/**
 * ENC() 密文格式判定。
 *
 * <p>与 {@code JasyptEncryptor#isEncrypted} 同一口径：{@code ENC(} 开头、{@code )}
 * 结尾、且括号内非空。配置面（PropertySource 钩子）与工具面（JasyptEncryptor）共用同一判定，
 * 避免「工具认为加密、钩子认为明文」的缝。</p>
 */
public final class EncryptedValueDetector {

    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";

    private EncryptedValueDetector() {
    }

    /**
     * @param value 待判定值（允许 null）
     * @return 是否为 {@code ENC(...)} 格式
     */
    public static boolean isEncrypted(String value) {
        return value != null
                && value.startsWith(ENC_PREFIX)
                && value.endsWith(ENC_SUFFIX)
                && value.length() > ENC_PREFIX.length() + ENC_SUFFIX.length();
    }

    /**
     * 剥掉 {@code ENC( )} 外壳，返回内层密文（Base64）。
     * 仅应在 {@link #isEncrypted(String)} 为 true 时调用。
     *
     * @param encValue {@code ENC(...)} 格式的值
     * @return 内层密文字符串
     */
    public static String unwrap(String encValue) {
        return encValue.substring(ENC_PREFIX.length(), encValue.length() - ENC_SUFFIX.length());
    }
}
