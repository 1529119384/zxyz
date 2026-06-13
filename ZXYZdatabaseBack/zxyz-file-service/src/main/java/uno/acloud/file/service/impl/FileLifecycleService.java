package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.FileNodeType;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.service.FileLifecyclePort;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.file.util.TransactionUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileLifecycleService implements FileLifecyclePort {

    private final FileMapper fileMapper;
    private final FileDomainValidator fileDomainValidator;
    private final ShareCleanupClient shareCleanupClient;
    private final FileAccessGuard fileAccessGuardService;
    private final FileObjectReferenceManager fileObjectReferenceService;
    private final FileConverter fileConverter;
    private final FileResourceChangedPublisher fileResourceChangedPublisher;
    private final TransactionHelper transactionHelper;

    public FileLifecycleService(FileMapper fileMapper, FileDomainValidator fileDomainValidator,
                                ShareCleanupClient shareCleanupClient, FileAccessGuard fileAccessGuardService,
                                FileObjectReferenceManager fileObjectReferenceService, FileConverter fileConverter,
                                Optional<FileResourceChangedPublisher> fileResourceChangedPublisher,
                                TransactionHelper transactionHelper) {
        this.fileMapper = fileMapper;
        this.fileDomainValidator = fileDomainValidator;
        this.shareCleanupClient = shareCleanupClient;
        this.fileAccessGuardService = fileAccessGuardService;
        this.fileObjectReferenceService = fileObjectReferenceService;
        this.fileConverter = fileConverter;
        this.fileResourceChangedPublisher = fileResourceChangedPublisher.orElse(null);
        this.transactionHelper = transactionHelper;
    }

    @Override
    public int logicalDelete(List<Long> fileIds, Long userId) {
        List<Long> normalizedFileIds = fileDomainValidator.normalizeFileIds(fileIds);
        List<FileNode> fileNodes = fileDomainValidator.requireNodes(normalizedFileIds);
        fileAccessGuardService.requireDeleteAccess(fileNodes, userId);
        for (FileNode fileNode : fileNodes) {
            if (Integer.valueOf(FileDeleteStatus.DELETED).equals(fileNode.getDeleted())) {
                throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "文件已被彻底删除");
            }
        }
        List<Long> allIds = collectDescendantIds(normalizedFileIds);
        int updatedRows = transactionHelper.execute(status -> {
            int rows = fileMapper.logicalDeleteByIds(allIds, userId);
            if (rows != allIds.size()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "删除文件失败");
            }
            publishByIdsAfterCommit("DELETED", normalizedFileIds);
            log.info("逻辑删除文件 roots={}, allIds={}", normalizedFileIds, allIds);
            return rows;
        });
        shareCleanupClient.deleteShareItemsByFileIds(allIds);
        return updatedRows;
    }

    @Override
    public int reallyDelete(List<Long> fileIds, Long userId) {
        List<Long> normalizedFileIds = fileDomainValidator.normalizeFileIds(fileIds);
        List<FileNode> fileNodes = fileDomainValidator.requireNodes(normalizedFileIds);
        List<FileInfoDTO> snapshots = fileNodes.stream()
                .map(fileConverter::toFileInfoDTO)
                .toList();
        fileAccessGuardService.requireDeleteAccess(fileNodes, userId);
        for (FileNode fileNode : fileNodes) {
            if (Integer.valueOf(FileDeleteStatus.DELETED).equals(fileNode.getDeleted())) {
                throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "文件已被彻底删除");
            }
        }
        List<Long> allIds = collectDescendantIds(normalizedFileIds);
        int updatedRows = transactionHelper.execute(status -> {
            List<String> ossKeys = fileMapper.getOssKeysByIds(allIds);
            int rows = fileMapper.reallyDeleteByIds(allIds, userId);
            if (rows != allIds.size()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "彻底删除文件失败");
            }
            fileObjectReferenceService.releaseReferences(ossKeys);
            publishFromSnapshotsAfterCommit("DELETED", snapshots);
            log.info("彻底删除文件 roots={}, allIds={}", normalizedFileIds, allIds);
            return rows;
        });
        shareCleanupClient.deleteShareItemsByFileIds(allIds);
        return updatedRows;
    }

    @Override
    public int restoreFiles(List<Long> fileIds, long userId) {
        List<Long> normalizedFileIds = fileDomainValidator.normalizeFileIds(fileIds);
        List<FileNode> fileNodes = fileDomainValidator.requireNodes(normalizedFileIds);
        fileAccessGuardService.requireDeleteAccess(fileNodes, userId);
        for (FileNode fileNode : fileNodes) {
            if (Integer.valueOf(FileDeleteStatus.DELETED).equals(fileNode.getDeleted())) {
                throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "文件已被彻底删除");
            }
            if (!Integer.valueOf(FileDeleteStatus.RECYCLE).equals(fileNode.getDeleted())) {
                throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "当前文件不在回收站中");
            }
        }
        List<Long> allIds = collectDescendantIds(normalizedFileIds);
        return transactionHelper.execute(status -> {
            List<FileNode> allNodes = fileMapper.getFileNodesByIds(allIds);
            Map<Long, String> renameMap = buildRestoreRenameMap(allNodes);
            applyRestoreRename(renameMap, allNodes);
            int updatedRows = fileMapper.restoreByIds(allIds);
            if (updatedRows != allIds.size()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "恢复文件失败");
            }
            publishByIdsAfterCommit("RESTORED", normalizedFileIds);
            log.info("恢复文件 roots={}, allIds={}, userId={}", normalizedFileIds, allIds, userId);
            return updatedRows;
        });
    }

    private List<Long> collectDescendantIds(List<Long> rootIds) {
        List<Long> allIds = fileMapper.collectDescendantIds(rootIds);
        if (allIds == null || allIds.isEmpty() || !new HashSet<>(allIds).containsAll(rootIds)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "收集文件树节点失败");
        }
        return List.copyOf(new LinkedHashSet<>(allIds));
    }

    private Map<Long, String> buildRestoreRenameMap(List<FileNode> fileNodes) {
        Map<Long, String> renameMap = new HashMap<>();
        Map<RestoreNameScope, Set<String>> reservedNamesByScope = new HashMap<>();
        for (FileNode fileNode : fileNodes) {
            Integer fileType = fileNode.isFolder() ? FileNodeType.FOLDER : FileNodeType.FILE;
            SpaceTarget target = SpaceTarget.fromNode(fileNode);
            Long ownerUserId = target.ownerUserId(fileNode.getUploadUserId());
            RestoreNameScope scope = new RestoreNameScope(fileNode.getParentId(), target, fileType, ownerUserId);
            Set<String> reservedNames = reservedNamesByScope.computeIfAbsent(scope, ignored -> new HashSet<>());
            String resolvedName = fileDomainValidator.resolveAvailableName(
                    fileNode.getParentId(),
                    target,
                    fileType,
                    fileNode.getOriginalName(),
                    reservedNames,
                    ownerUserId
            );
            reservedNames.add(resolvedName);
            if (!fileNode.getOriginalName().equals(resolvedName)) {
                renameMap.put(fileNode.getId(), resolvedName);
                log.info("恢复文件 {} 时检测到重名，自动重命名为 {}", fileNode.getOriginalName(), resolvedName);
            }
        }
        return renameMap;
    }

    private void applyRestoreRename(Map<Long, String> renameMap, List<FileNode> fileNodes) {
        if (renameMap.isEmpty()) {
            return;
        }
        int updatedRows = fileMapper.batchRenameByIds(renameMap);
        if (updatedRows != renameMap.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "恢复文件重命名失败");
        }

        Map<Long, FileNode> fileNodeMap = fileNodes.stream()
                .collect(Collectors.toMap(FileNode::getId, fileNode -> fileNode, (left, right) -> left));
        List<Map.Entry<Long, String>> renameEntries = renameMap.entrySet().stream()
                .sorted((left, right) -> Integer.compare(
                        storePathLength(fileNodeMap.get(right.getKey())),
                        storePathLength(fileNodeMap.get(left.getKey()))
                ))
                .toList();
        for (Map.Entry<Long, String> entry : renameEntries) {
            FileNode fileNode = fileNodeMap.get(entry.getKey());
            if (fileNode == null) {
                continue;
            }
            String oldStorePath = fileNode.getStorePath();
            String newStorePath = buildRenamedStorePath(fileNode, entry.getValue());
            int pathUpdatedRows = fileMapper.updateStorePathById(fileNode.getId(), newStorePath);
            if (pathUpdatedRows != 1) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "恢复文件路径更新失败");
            }
            if (fileNode instanceof Folder) {
                fileMapper.renameDescendantStorePaths(oldStorePath, newStorePath);
            }
        }
    }

    private int storePathLength(FileNode fileNode) {
        return fileNode == null || fileNode.getStorePath() == null ? 0 : fileNode.getStorePath().length();
    }

    private String buildRenamedStorePath(FileNode fileNode, String resolvedName) {
        String storePath = FilePathUtil.safeStorePath(fileNode.getStorePath());
        int separatorIndex = storePath.lastIndexOf('/');
        if (separatorIndex <= 0) {
            return FilePathUtil.normalizeStorePathSegment("/" + resolvedName);
        }
        return FilePathUtil.normalizeStorePathSegment(storePath.substring(0, separatorIndex) + "/" + resolvedName);
    }

    private void publishByIdsAfterCommit(String eventType, List<Long> fileIds) {
        if (fileResourceChangedPublisher == null || fileIds == null || fileIds.isEmpty()) {
            return;
        }
        List<Long> eventFileIds = List.copyOf(fileIds);
        TransactionUtils.runAfterCommit(() -> fileResourceChangedPublisher.publishByIds(eventType, eventFileIds));
    }

    private void publishFromSnapshotsAfterCommit(String eventType, List<FileInfoDTO> snapshots) {
        if (fileResourceChangedPublisher == null || snapshots == null || snapshots.isEmpty()) {
            return;
        }
        List<FileInfoDTO> eventSnapshots = List.copyOf(snapshots);
        TransactionUtils.runAfterCommit(() -> fileResourceChangedPublisher.publishFromSnapshots(eventType, eventSnapshots));
    }

    private record RestoreNameScope(Long parentId, SpaceTarget target, Integer fileType, Long ownerUserId) {
    }
}
