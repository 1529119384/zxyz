package uno.acloud.file.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.mapper.FileMapper;

@RequiredArgsConstructor
@Component
public class FilePathResolver {

    private final FileMapper fileMapper;
    private final FileDomainValidator fileDomainValidator;

    private final java.util.Map<Long, FileNode> parentCache = new java.util.HashMap<>();

    public String buildStorePath(Long parentId, String currentName) {
        String normalizedName = fileDomainValidator.validateInputName(currentName);
        if (parentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "parentId 不能为空");
        }
        if (Long.valueOf(-1L).equals(parentId)) {
            return FilePathUtil.normalizeStorePathSegment(normalizedName);
        }

        FileNode parentInfo = parentCache.computeIfAbsent(parentId, fileMapper::getFileNodeById);
        if (parentInfo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "父级目录不存在，无法生成存储路径");
        }

        String parentPath = parentInfo.getStorePath();
        if (parentPath == null || parentPath.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "父级目录路径无效，无法生成存储路径");
        }
        return FilePathUtil.normalizeStorePathSegment(parentPath + "/" + normalizedName);
    }

    public void clearCache() {
        parentCache.clear();
    }
}
