package uno.acloud.common.util;

/**
 * 日志脱敏工具类，用于在日志中遮盖敏感信息（PII）。
 */
public final class LogMaskingUtil {

    private LogMaskingUtil() {}

    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (local.length() <= 1) {
            return "*" + domain;
        }
        return local.charAt(0) + "***" + domain;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 7) {
            return phone.replaceAll(".", "*");
        }
        int start = phone.indexOf(digits.charAt(0));
        String prefix = phone.substring(0, start);
        return prefix + digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    public static String maskUsername(String username) {
        if (username == null || username.isEmpty()) {
            return username;
        }
        if (username.length() <= 2) {
            return username.charAt(0) + "*";
        }
        return username.charAt(0) + "*".repeat(username.length() - 2) + username.charAt(username.length() - 1);
    }
}
