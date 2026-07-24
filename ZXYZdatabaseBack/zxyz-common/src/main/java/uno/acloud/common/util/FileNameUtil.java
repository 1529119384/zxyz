package uno.acloud.common.util;

import java.util.UUID;

public class FileNameUtil {

    private FileNameUtil() {}

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
