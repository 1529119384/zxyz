package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.vo.BatchOperationDetailVO;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileMoveService {

    private final FileMapper fileMapper;
    private final FileDomainValidator fileDomainValidator;
    private final FilePathResolver filePathResolver;
    private final FileAccessGuard fileAccessGuardService;
    private final FileOperationHelper helper;
    private final TransactionHelper transactionHelper;

    public FileMoveService(FileMapper fileMapper,
                           FileDomainValidator fileDomainValidator,
                           FilePathResolver filePathResolver,
                           FileAccessGuard fileAccessGuardService,
                           FileOperationHelper helper,
                           TransactionHelper transactionHelper) {
        this.fileMapper = fileMapper;
        this.fileDomainValidator = fileDomainValidator;
        this.filePathResolver = filePathResolver;
        this.fileAccessGuardService = fileAccessGuardService;
        this.helper = helper;
        this.transactionHelper = transactionHelper;
    }

    public BatchOperationDetailVO moveFiles(List<Long> fileIds, Long targetParentId, Long requestedTeamId, Long userId) {
        return moveFiles(fileIds, targetParentId, requestedTeamId, null, null, userId);
    }

    public BatchOperationDetailVO moveFiles(List<Long> fileIds, Long targetParentId, Long requestedTeamId, Integer requestedSpaceType, Long requestedProjectId, Long userId) {
        fileDomainValidator.validateUserId(userId);
        fileDomainValidator.validateTargetParentId(targetParentId);

        List<FileNode> selectedNodes = fileDomainValidator.requireMovableNodes(fileIds);
        fileAccessGuardService.requireWriteAccess(selectedNodes, userId);
        Folder targetFolder = fileDomainValidator.requireTargetFolder(targetParentId);
        if (targetFolder != null) {
            fileAccessGuardService.requireWriteAccess(targetFolder, userId);
        }
        SpaceTarget target = helper.resolveOperationTarget(targetParentId, requestedTeamId, requestedSpaceType, requestedProjectId, targetFolder);
        helper.requireTargetWriteAccess(target, userId);
        fileAccessGuardService.requireSameSpace(selectedNodes, target.teamId());
        List<FileNode> topLevelNodes = FilePathUtil.reduceToTopLevelNodes(selectedNodes);

        // PRELOAD all descendants for top-level folder nodes (single SQL via recursive CTE)
        List<Long> folderParentIds = topLevelNodes.stream()
                .filter(n -> n instanceof Folder)
                .map(FileNode::getId)
                .collect(Collectors.toList());
        final Map<Long, List<FileNode>> childrenMap;
        if (!folderParentIds.isEmpty()) {
            List<FileNode> allDescendants = fileMapper.collectDescendantNodes(folderParentIds);
            childrenMap = helper.buildChildrenMap(allDescendants);
        } else {
            childrenMap = Map.of();
        }

        return transactionHelper.execute(status ->
                executeMoveInTransaction(fileIds, topLevelNodes, targetParentId, target, targetFolder, childrenMap, userId));
    }

    private BatchOperationDetailVO executeMoveInTransaction(List<Long> fileIds,
                                                            List<FileNode> topLevelNodes,
                                                            Long targetParentId,
                                                            SpaceTarget target,
                                                            Folder targetFolder,
                                                            Map<Long, List<FileNode>> childrenMap,
                                                            Long userId) {
        FileOperationHelper.MoveTargetContext targetContext = new FileOperationHelper.MoveTargetContext(targetParentId, target);

        for (FileNode fileNode : topLevelNodes) {
            if (isSameOperationTarget(fileNode, targetContext)) {
                targetContext.record(fileNode, FileOperationHelper.ACTION_MOVED, fileNode.getOriginalName(), false,
                        FileOperationHelper.STATUS_SKIPPED, null, "已在目标目录中");
                continue;
            }
            try {
                fileDomainValidator.validateFolderTarget(fileNode, targetFolder);
                moveSingleNode(fileNode, targetContext, userId, childrenMap);
            } catch (BusinessException e) {
                if (!targetContext.hasDetail(fileNode.getId())) {
                    targetContext.record(fileNode, FileOperationHelper.ACTION_MOVED, fileNode.getOriginalName(), false,
                            FileOperationHelper.STATUS_FAIL, e.getErrorCode(), e.getMessage());
                }
                throw helper.withBatchData(e, targetContext);
            }
        }
        BatchOperationDetailVO result = helper.buildBatchResult(targetContext.details(), targetParentId);
        helper.publishByIdsAfterCommit("MOVED", fileIds);
        return result;
    }

    private void moveSingleNode(FileNode fileNode, FileOperationHelper.MoveTargetContext targetContext, Long userId, Map<Long, List<FileNode>> childrenMap) {
        String resolvedName = fileNode.getOriginalName();
        boolean renamed = false;
        try {
            resolvedName = helper.resolveMoveName(fileNode, targetContext, userId);
            renamed = helper.isRenamed(fileNode, resolvedName);
            String newStorePath = filePathResolver.buildStorePath(targetContext.parentId(), resolvedName);
            int updatedRows = fileMapper.moveNodeById(
                    fileNode.getId(),
                    resolvedName,
                    targetContext.parentId(),
                    newStorePath,
                    targetContext.target().teamId(),
                    targetContext.target().spaceType(),
                    targetContext.target().projectId()
            );
            if (updatedRows != 1) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "移动文件失败");
            }

            if (fileNode instanceof Folder) {
                updateDescendantStorePaths(fileNode.getId(), newStorePath, targetContext.target(), childrenMap);
            }
            targetContext.reserve(fileNode.getFileType(), resolvedName);
            targetContext.record(fileNode, FileOperationHelper.ACTION_MOVED, resolvedName, renamed,
                    FileOperationHelper.STATUS_SUCCESS, ErrorCode.SUCCESS, FileOperationHelper.STATUS_SUCCESS);
        } catch (BusinessException e) {
            targetContext.record(fileNode, FileOperationHelper.ACTION_MOVED, resolvedName, renamed,
                    FileOperationHelper.STATUS_FAIL, e.getErrorCode(), e.getMessage());
            throw e;
        }
    }

    private void updateDescendantStorePaths(Long sourceParentId, String parentStorePath, SpaceTarget target, Map<Long, List<FileNode>> childrenMap) {
        helper.walkDescendantsPreloaded(sourceParentId, childrenMap, parentStorePath, (child, currentParentStorePath) -> {
            String childStorePath = FilePathUtil.normalizeStorePathSegment(currentParentStorePath + "/" + child.getOriginalName());
            int updatedRows = fileMapper.updateStorePathAndSpaceById(
                    child.getId(),
                    childStorePath,
                    target.teamId(),
                    target.spaceType(),
                    target.projectId()
            );
            if (updatedRows != 1) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "更新子节点路径失败");
            }
            return childStorePath;
        });
    }

    private boolean isSameOperationTarget(FileNode fileNode, FileOperationHelper.MoveTargetContext targetContext) {
        return fileDomainValidator.isSameParent(fileNode, targetContext.parentId())
                && SpaceTarget.fromNode(fileNode).equals(targetContext.target());
    }
}
