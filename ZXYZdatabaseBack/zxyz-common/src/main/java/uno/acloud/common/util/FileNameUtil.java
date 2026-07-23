package uno.acloud.common.util;

import java.util.Set;
import java.util.UUID;

public class FileNameUtil {

    private FileNameUtil() {}

    public static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".exe", ".bat", ".cmd", ".scr", ".pif", ".com",
            ".js", ".vbs", ".vbe", ".ps1", ".psm1", ".msi",
            ".wsf", ".wsh", ".hta", ".cpl", ".msc", ".reg"
    );

    public static String uuidName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        int dotIndex = originalName.lastIndexOf('.');
        String extension = dotIndex >= 0 && dotIndex < originalName.length() - 1
                ? originalName.substring(dotIndex)
                : "";
        return UUID.randomUUID().toString().replace("-", "") + extension;
    }
}
