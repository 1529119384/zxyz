package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.infrastructure.persistence.entity.FileCardContent;

public final class FileCardContentUtils {

    private FileCardContentUtils() {
    }

    public static FileCardContent parse(ObjectMapper objectMapper, String rawContent) {
        try {
            return objectMapper.readValue(rawContent, FileCardContent.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件卡片消息内容损坏");
        }
    }
}
