package uno.acloud.file.util;

import org.springframework.lang.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 文件类型工具类
 * 返回：
 * 0 压缩包(zip/rar)
 * 1 word(doc/docx)
 * 2 ppt(ppt/pptx)
 * 3 excel(xls/xlsx)
 * 4 pdf
 * 5 图片(png/jpg/jpeg)
 * 6 音频(mp3)
 * 7 视频(mp4)
 * 8 txt
 * 9 其他/未知
 */
public final class FileTypeUtil {

    private FileTypeUtil() {}

    private static final Map<String, FileType> MAGIC_MAP = new HashMap<>();
    private static final Map<String, FileType> EXT_MAP = new HashMap<>();

    static {
        /* --- 压缩包 --- */
        MAGIC_MAP.put("504b0304", FileType.ARCHIVE);   // zip
        MAGIC_MAP.put("52617221", FileType.ARCHIVE);   // rar

        /* --- 图片 --- */
        MAGIC_MAP.put("89504e47", FileType.IMAGE);     // png
        MAGIC_MAP.put("ffd8ffe0", FileType.IMAGE);     // jpg
        MAGIC_MAP.put("ffd8ffe1", FileType.IMAGE);
        MAGIC_MAP.put("ffd8ffe2", FileType.IMAGE);

        /* --- PDF --- */
        MAGIC_MAP.put("25504446", FileType.PDF);       // %PDF

        /* --- 音频 --- */
        MAGIC_MAP.put("49443303", FileType.AUDIO);     // ID3  -> mp3

        /* --- 视频 --- */
        MAGIC_MAP.put("00000018667479704d534e56", FileType.VIDEO); // ftypmsnv
        MAGIC_MAP.put("000000186674797069736f6d", FileType.VIDEO); // ftypisom
        MAGIC_MAP.put("000000206674797069736f6d", FileType.VIDEO); // 20 字节偏移
    }

    static {
        EXT_MAP.put("zip", FileType.ARCHIVE);
        EXT_MAP.put("rar", FileType.ARCHIVE);
        EXT_MAP.put("doc", FileType.DOCUMENT);
        EXT_MAP.put("docx", FileType.DOCUMENT);
        EXT_MAP.put("md", FileType.DOCUMENT);
        EXT_MAP.put("ppt", FileType.PRESENTATION);
        EXT_MAP.put("pptx", FileType.PRESENTATION);
        EXT_MAP.put("xls", FileType.SPREADSHEET);
        EXT_MAP.put("xlsx", FileType.SPREADSHEET);
        EXT_MAP.put("pdf", FileType.PDF);
        EXT_MAP.put("png", FileType.IMAGE);
        EXT_MAP.put("jpg", FileType.IMAGE);
        EXT_MAP.put("jpeg", FileType.IMAGE);
        EXT_MAP.put("mp3", FileType.AUDIO);
        EXT_MAP.put("mp4", FileType.VIDEO);
        EXT_MAP.put("txt", FileType.TEXT);
    }

    /**
     * 根据文件头魔数和扩展名判断文件类型，返回类型码（兼容旧接口）。
     */
    public static int classify(InputStream in, String originalFilename) {
        return classifyAsType(in, originalFilename).getCode();
    }

    /**
     * 根据文件头魔数和扩展名判断文件类型，返回 {@link FileType} 枚举。
     */
    public static FileType classifyAsType(InputStream in, String originalFilename) {
        String ext = getExtension(originalFilename);
        if (ext != null) {
            FileType type = EXT_MAP.get(ext.toLowerCase(Locale.ROOT));
            if (type != null) return type;
        }

        if (in != null && in.markSupported()) {
            try {
                byte[] header = new byte[28];
                in.mark(32);
                int read = in.read(header);
                in.reset();
                if (read > 0) {
                    String hex = bytesToHex(header, read);
                    FileType magicType = MAGIC_MAP.get(hex);
                    if (magicType != null) return magicType;
                }
            } catch (IOException ignore) {
            }
        }

        return FileType.OTHER;
    }

    private static String bytesToHex(byte[] src, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", src[i]));
        }
        return sb.toString();
    }

    @Nullable
    private static String getExtension(@Nullable String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        return (dot == -1 || dot == filename.length() - 1) ? null
                : filename.substring(dot + 1);
    }
}
