package uno.acloud.common.util.oss;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class OssContentDispositionUtil {

    private OssContentDispositionUtil() {
    }

    public static String buildAttachmentFileName(String filename) {
        String downloadFileName = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename*=utf-8''" + downloadFileName;
    }

    public static String buildInlineFileName(String filename) {
        String inlineFileName = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "inline; filename*=utf-8''" + inlineFileName;
    }
}
