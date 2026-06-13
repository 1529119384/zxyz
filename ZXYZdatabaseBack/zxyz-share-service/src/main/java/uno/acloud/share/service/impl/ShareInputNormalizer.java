package uno.acloud.share.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ShareInputNormalizer {

    public List<Long> normalizeFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileIds 不能为空");
        }
        Set<Long> uniqueFileIds = new LinkedHashSet<>();
        for (Long fileId : fileIds) {
            if (fileId == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "fileIds 中存在空值");
            }
            if (!uniqueFileIds.add(fileId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "fileIds 不能包含重复值");
            }
        }
        return List.copyOf(uniqueFileIds);
    }

    @Nullable
    public LocalDateTime resolveExpireTime(String expireType, LocalDateTime now) {
        if (StringUtils.isBlank(expireType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "expireType 不能为空");
        }
        return switch (expireType.trim().toLowerCase(Locale.ROOT)) {
            case "1d" -> now.plusDays(1);
            case "7d" -> now.plusDays(7);
            case "30d" -> now.plusDays(30);
            case "forever" -> null;
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "expireType 非法");
        };
    }

    @Nullable
    public Integer resolveMaxAccessCount(@Nullable Integer maxAccessCount) {
        if (maxAccessCount == null || maxAccessCount == 0) {
            return null;
        }
        if (maxAccessCount < 1 || maxAccessCount > 99) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "maxAccessCount 必须在 1 到 99 之间");
        }
        return maxAccessCount;
    }

    public String normalizePassword(String password) {
        if (StringUtils.isBlank(password)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提取码必须为 4 位字母或数字");
        }
        String normalized = password.trim();
        if (!normalized.matches("[A-Za-z0-9]{4}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提取码必须为 4 位字母或数字");
        }
        return normalized;
    }

    public String normalizePath(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (StringUtils.isBlank(normalized)) {
            return "";
        }
        return String.join("/", splitPath(normalized));
    }

    public List<String> splitPath(String normalizedPath) {
        List<String> result = new ArrayList<>();
        for (String segment : normalizedPath.split("/")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty() || ".".equals(trimmed) || "..".equals(trimmed)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "path 非法");
            }
            result.add(trimmed);
        }
        return result;
    }
}
