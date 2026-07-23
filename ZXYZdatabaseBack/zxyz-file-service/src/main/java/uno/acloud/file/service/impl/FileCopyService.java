package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.vo.BatchOperationDetailVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileCopyService {

    /**
     * Maximum number of file/folder nodes allowed in a single copy transaction.
     * Beyond this limit the operation is rejected to avoid excessively long transactions.
     * <p>可通过热配置 {@code app.file.copy.max-nodes-per-transaction} 动态调整。</p>
     */
    private static final int FALLBACK_MAX_COPY_NODES_PER_TRANSACTION = 500;

    private final FileMapper fileMapper;
    private final FileDomainValidator fileDomainValidator;
    private final FilePathResolver filePathResolver;
    private final FileAccessGuard fileAccessGuardService;
    private final FileObjectReferenceManager fileObjectReferenceService;
    private final FileOperationHelper helper;
    private final TransactionTemplate transactionTemplate;
    private final ConfigGetter configGetter;
    private final int maxCopyNodesPerTransaction;

    public FileCopyService(FileMapper fileMapper,
                           FileDomainValidator fileDomainValidator,
                           FilePathResolver filePathResolver,
                           FileAccessGuard fileAccessGuardService,
                           FileObjectReferenceManager fileObjectReferenceService,
                           FileOperationHelper helper,
                           TransactionTemplate transactionTemplate,
                           ConfigGetter configGetter) {
        this.fileMapper = fileMapper;
        this.fileDomainValidator = fileDomainValidator;
        this.filePathResolver = filePathResolver;
        this.fileAccessGuardService = fileAccessGuardService;
        this.fileObjectReferenceService = fileObjectReferenceService;
        this.helper = helper;
        this.transactionTemplate = transactionTemplate;
        this.configGetter = configGetter;
        this.maxCopyNodesPerTransaction = configGetter.getInt("app.file.copy.max-nodes-per-transaction", FALLBACK_MAX_COPY_NODES_PER_TRANSACTION);
    }

    public BatchOperationDetailVO copyFiles(List<Long> fileIds, Long targetParentId, Long requestedTeamId, Long userId) {
        return copyFiles(fileIds, targetParentId, requestedTeamId, null, null, userId);
    }

    public BatchOperationDetailVO copyFiles(List<Long> fileIds, Long targetParentId, Long requestedTeamId, Integer requestedSpaceType, Long requestedProjectId, Long userId) {
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
        Map<Long, List<FileNode>> childrenMap = Map.of();
        if (!folderParentIds.isEmpty()) {
            List<FileNode> allDescendants = fileMapper.collectDescendantNodes(folderParentIds);
            childrenMap = helper.buildChildrenMap(allDescendants);
        }

        // Guard: reject excessively large copy operations to avoid long-running transactions
        int totalNodes = topLevelNodes.size() + childrenMap.values().stream().mapToInt(List::size).sum();
        if (totalNodes > this.maxCopyNodesPerTransaction) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "单次复制文件数量过多（" + totalNodes + "），请分批操作（上限 " + this.maxCopyNodesPerTransaction + "）");
        }

        return executeCopyInTransaction(topLevelNodes, targetParentId, target, targetFolder, childrenMap, userId);
    }

    public BatchOperationDetailVO executeCopyInTransaction(List<FileNode> topLevelNodes,
                                                            Long targetParentId,
                                                            SpaceTarget target,
                                                            Folder targetFolder,
                                                            Map<Long, List<FileNode>> childrenMap,
                                                            Long userId) {
        FileOperationHelper.CopyTargetContext rootTargetContext = new FileOperationHelper.CopyTargetContext(targetParentId, target);
        List<BatchOperationDetailVO.ItemDetail> details = new ArrayList<>();

        // 注意：分批独立事务，后续批次失败时前面批次已提交（部分成功语义）。
        // 调用方应通过返回的 BatchOperationDetailVO 中的 status 字段判断每个节点的处理结果。
        List<List<FileNode>> batches = partition(topLevelNodes, 30);
        for (List<FileNode> batch : batches) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    for (FileNode fileNode : batch) {
                        fileDomainValidator.validateFolderTarget(fileNode, targetFolder);
                        String resolvedName = copySingleNode(fileNode, rootTargetContext, userId, childrenMap);
                        details.add(helper.buildDetail(fileNode, FileOperationHelper.ACTION_COPIED, resolvedName,
                                helper.isRenamed(fileNode, resolvedName),
                                FileOperationHelper.STATUS_SUCCESS, ErrorCode.SUCCESS, FileOperationHelper.STATUS_SUCCESS));
                    }
                });
            } catch (BusinessException e) {
                throw helper.withBatchData(e, details, targetParentId);
            }
        }
        return helper.buildBatchResult(details, targetParentId);
    }

    private static <T> List<List<T>> partition(List<T> list, int batchSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }

    private String copySingleNode(FileNode source, FileOperationHelper.CopyTargetContext targetContext, Long userId, Map<Long, List<FileNode>> childrenMap) {
        LocalDateTime now = LocalDateTime.now();
        String resolvedName = helper.resolveCopyName(source, targetContext, userId);
        if (source instanceof FileItem fileItem) {
            cloneFileItem(fileItem, targetContext.parentId(), targetContext.target(), resolvedName, userId, now);
            return resolvedName;
        }

        Folder copiedRoot = cloneFolder((Folder) source, targetContext.parentId(), targetContext.target(), resolvedName, userId, now);
        copyChildrenRecursively(source.getId(), new FileOperationHelper.CopyTargetContext(copiedRoot.getId(), targetContext.target()), userId, now, childrenMap);
        return resolvedName;
    }

    private FileItem cloneFileItem(FileItem source,
                                   Long targetParentId,
                                   SpaceTarget target,
                                   String resolvedName,
                                   Long userId,
                                   LocalDateTime now) {
        FileItem clone = FileItem.create();
        clone.setUuidName(source.getUuidName());
        clone.setOriginalName(resolvedName);
        clone.setCategory(source.getCategory());
        clone.setFileSize(source.getFileSize());
        clone.setFileUrl(source.getFileUrl());
        clone.setStorePath(filePathResolver.buildStorePath(targetParentId, resolvedName));
        clone.setUploadUserId(userId);
        clone.setTeamId(target.teamId());
        clone.setSpaceType(target.spaceType());
        clone.setProjectId(target.projectId());
        clone.setSharedUserId(source.getSharedUserId());
        clone.setDeletedUserId(null);
        clone.setParentId(targetParentId);
        clone.setCreateTime(now);
        clone.setModifyTime(now);
        clone.setDeleted(FileDeleteStatus.NORMAL);

        Integer insertedRows = fileMapper.insertFileItem(clone);
        if (insertedRows == null || insertedRows != 1 || clone.getId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "复制文件失败");
        }
        fileObjectReferenceService.retainReference(clone.getUuidName());
        return clone;
    }

    private Folder cloneFolder(Folder source,
                               Long targetParentId,
                               SpaceTarget target,
                               String resolvedName,
                               Long userId,
                               LocalDateTime now) {
        Folder clone = Folder.create();
        clone.setOriginalName(resolvedName);
        clone.setStorePath(filePathResolver.buildStorePath(targetParentId, resolvedName));
        clone.setUploadUserId(userId);
        clone.setTeamId(target.teamId());
        clone.setSpaceType(target.spaceType());
        clone.setProjectId(target.projectId());
        clone.setSharedUserId(source.getSharedUserId());
        clone.setDeletedUserId(null);
        clone.setParentId(targetParentId);
        clone.setCreateTime(now);
        clone.setModifyTime(now);
        clone.setDeleted(FileDeleteStatus.NORMAL);

        Integer insertedRows = fileMapper.insertFolder(clone);
        if (insertedRows == null || insertedRows != 1 || clone.getId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "复制文件夹失败");
        }
        return clone;
    }

    private void copyChildrenRecursively(Long sourceParentId, FileOperationHelper.CopyTargetContext targetContext, Long userId, LocalDateTime now, Map<Long, List<FileNode>> childrenMap) {
        helper.walkDescendantsPreloaded(sourceParentId, childrenMap, targetContext, (child, currentTargetContext) -> {
            String resolvedName = helper.resolveCopyName(child, currentTargetContext, userId);
            if (child instanceof FileItem fileItem) {
                cloneFileItem(fileItem, currentTargetContext.parentId(), currentTargetContext.target(), resolvedName, userId, now);
                return currentTargetContext;
            }
            Folder copiedChild = cloneFolder((Folder) child, currentTargetContext.parentId(), currentTargetContext.target(), resolvedName, userId, now);
            return new FileOperationHelper.CopyTargetContext(copiedChild.getId(), currentTargetContext.target());
        });
    }
}
