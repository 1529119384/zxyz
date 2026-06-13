package uno.acloud.file.util;

/**
 * 文件类型枚举，与 {@link FileTypeUtil} 返回的整数码一一对应。
 */
public enum FileType {

    ARCHIVE(0, "压缩包"),
    DOCUMENT(1, "文档"),
    PRESENTATION(2, "演示文稿"),
    SPREADSHEET(3, "电子表格"),
    PDF(4, "PDF"),
    IMAGE(5, "图片"),
    AUDIO(6, "音频"),
    VIDEO(7, "视频"),
    TEXT(8, "文本"),
    OTHER(9, "其他");

    private final int code;
    private final String label;

    FileType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 根据整数码查找枚举值，未知码返回 {@link #OTHER}。
     */
    public static FileType fromCode(int code) {
        for (FileType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return OTHER;
    }
}
