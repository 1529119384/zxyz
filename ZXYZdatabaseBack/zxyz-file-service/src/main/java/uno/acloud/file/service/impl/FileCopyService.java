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
import uno.acloud.file.infrastructure.entity.UsageLedger;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;
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
     * <p>可通过热配置 {@code app.file.copy.max-nodes-per-tx} 动态调整。</p>
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
    private final ProjectStorageCheckClient projectStorageCheckClient;
    private final UsageLedgerMapper usageLedgerMapper;
    private final int maxCopyNodesPerTransaction;

    public FileCopyService(FileMapper fileMapper,
                           FileDomainValidator fileDomainValidator,
                           FilePathResolver filePathResolver,
                           FileAccessGuard fileAccessGuardService,
                           FileObjectReferenceManager fileObjectReferenceService,
                           FileOperationHelper helper,
                           TransactionTemplate transactionTemplate,
                           ConfigGetter configGetter,
                           ProjectStorageCheckClient projectStorageCheckClient,
                           UsageLedgerMapper usageLedgerMapper) {
        this.fileMapper = fileMapper;
        this.fileDomainValidator = fileDomainValidator;
        this.filePathResolver = filePathResolver;
        this.fileAccessGuardService = fileAccessGuardService;
        this.fileObjectReferenceService = fileObjectReferenceService;
        this.helper = helper;
        this.transactionTemplate = transactionTemplate;
        this.configGetter = configGetter;
        this.projectStorageCheckClient = projectStorageCheckClient;
        this.usageLedgerMapper = usageLedgerMapper;
        this.maxCopyNodesPerTransaction = configGetter.getInt("app.file.copy.max-nodes-per-tx", FALLBACK_MAX_COPY_NODES_PER_TRANSACTION);
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
        List<FileNode> allDescendants = List.of();
        Map<Long, List<FileNode>> childrenMap = Map.of();
        if (!folderParentIds.isEmpty()) {
            allDescendants = fileMapper.collectDescendantNodes(folderParentIds);
            childrenMap = helper.buildChildrenMap(allDescendants);
        }

        // 计算待复制文件总大小并校验配额（allDescendants 已包含所有嵌套子级）
        long totalBytesToCopy = 0;
        for (FileNode descendant : allDescendants) {
            if (descendant instanceof FileItem fileItem && fileItem.getFileSize() != null) {
                totalBytesToCopy += fileItem.getFileSize();
            }
        }
        // 顶层文件项（不在 allDescendants 中，因 collectDescendantNodes 以 folderParentIds 为根）
        for (FileNode node : topLevelNodes) {
            if (node instanceof FileItem fileItem && fileItem.getFileSize() != null) {
                totalBytesToCopy += fileItem.getFileSize();
            }
        }
        // HTTP 预检仅作快速失败（避免明知超限还开大事务）；最终裁决交给批次事务内的 incrementWhenUnderLimit。
        // 同时把预检解析出的有效存储上限埋入台账，供同事务原子扣减守卫使用（P1-B2）。
        if (totalBytesToCopy > 0) {
            Long storageLimit = projectStorageCheckClient.checkQuota(
                    userId, target.teamId(), target.spaceType(), target.projectId(), totalBytesToCopy);
            upsertLedgerLimit(scopeKeyOf(target, userId), storageLimit);
        }

        // Guard: reject excessively large copy operations to avoid long-running transactions
        // allDescendants 不含顶层节点；顶层文件项需额外计入
        long totalNodes = topLevelNodes.size() + allDescendants.size();
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
        String scopeKey = scopeKeyOf(target, userId);

        // 注意：分批独立事务，后续批次失败时前面批次已提交（部分成功语义）。
        // 调用方应通过返回的 BatchOperationDetailVO 中的 status 字段判断每个节点的处理结果。
        // 因此配额扣减必须按批次落在各自事务内（不能在循环外一次性扣总量），否则某批回滚会造成台账泄漏。
        List<List<FileNode>> batches = partition(topLevelNodes, 30);
        for (List<FileNode> batch : batches) {
            // 记录本批开始前已累积的明细条数：批次回滚时可用它丢弃本批明细。
            int detailsMark = details.size();
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    ByteAccumulator batchBytes = new ByteAccumulator();
                    for (FileNode fileNode : batch) {
                        fileDomainValidator.validateFolderTarget(fileNode, targetFolder);
                        String resolvedName = copySingleNode(fileNode, rootTargetContext, userId, childrenMap, batchBytes);
                        details.add(helper.buildDetail(fileNode, FileOperationHelper.ACTION_COPIED, resolvedName,
                                helper.isRenamed(fileNode, resolvedName),
                                FileOperationHelper.STATUS_SUCCESS, ErrorCode.SUCCESS, FileOperationHelper.STATUS_SUCCESS));
                    }
                    // 事务提交前扣减：超限抛 BusinessException 回滚本批（含已插入的 file_node 行），
                    // 由外层 catch 捕获并携带批次明细。
                    chargeQuotaInTransaction(scopeKey, batchBytes.value());
                });
            } catch (RuntimeException e) {
                // 本批事务已整体回滚，其明细条目必须一并丢弃，否则返回值会把未落库的节点报成"成功"。
                details.subList(detailsMark, details.size()).clear();
                if (e instanceof BusinessException businessException) {
                    throw helper.withBatchData(businessException, details, targetParentId);
                }
                throw e;
            }
        }
        return helper.buildBatchResult(details, targetParentId);
    }

    /**
     * 批次事务内的原子配额扣减：与已落库的 file_node 行同生共死。
     * <p>先 ensure 兜底行缺失（配额服务未配置时 limit 为 NULL=不限制，不会覆盖已配置上限），
     * 再用条件 UPDATE 做"检查+扣减"原子守卫；受影响行数非 1 即超限或作用域缺失，抛异常回滚本批。
     * 错误码与语义对齐上传路径 FileUploadPersistenceManager.saveFileItem。</p>
     */
    private void chargeQuotaInTransaction(String scopeKey, long batchBytes) {
        if (batchBytes <= 0) {
            return;
        }
        usageLedgerMapper.ensureScopeAndLimit(scopeKey, null);
        int affected = usageLedgerMapper.incrementWhenUnderLimit(scopeKey, batchBytes);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "复制超过当前空间配额，请清理后重试");
        }
    }

    /**
     * 把 HTTP 预检解析出的有效存储上限埋入配额台账，供批次事务内的原子扣减守卫使用。
     * 写入失败不阻断复制（limit 为 NULL 时守卫退化为"不限制"，由小时级对账任务校正）。
     */
    private void upsertLedgerLimit(String scopeKey, Long storageLimit) {
        try {
            usageLedgerMapper.ensureScopeAndLimit(scopeKey, storageLimit);
        } catch (Exception ex) {
            log.warn("写入配额台账上限失败，本次按不限制处理: scopeKey={}", scopeKey, ex);
        }
    }

    /**
     * 与 file_node.scope_key 生成列、克隆行写入字段（uploadUserId/teamId/spaceType/projectId）
     * 严格对应的作用域键，对账任务按同一口径聚合 SUM(file_size)。
     */
    private static String scopeKeyOf(SpaceTarget target, Long userId) {
        return UsageLedger.scopeKeyOf(target.spaceType(), target.teamId(), target.projectId(), userId);
    }

    /** 批次内实际写入字节数的累加器（Folder 计 0，仅 FileItem 的 fileSize 计入）。 */
    private static final class ByteAccumulator {

        private long bytes;

        void add(FileItem copied) {
            if (copied != null && copied.getFileSize() != null) {
                bytes += copied.getFileSize();
            }
        }

        long value() {
            return bytes;
        }
    }

    private static <T> List<List<T>> partition(List<T> list, int batchSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }

    private String copySingleNode(FileNode source,
                                   FileOperationHelper.CopyTargetContext targetContext,
                                   Long userId,
                                   Map<Long, List<FileNode>> childrenMap,
                                   ByteAccumulator batchBytes) {
        LocalDateTime now = LocalDateTime.now();
        String resolvedName = helper.resolveCopyName(source, targetContext, userId);
        if (source instanceof FileItem fileItem) {
            batchBytes.add(cloneFileItem(fileItem, targetContext.parentId(), targetContext.target(), resolvedName, userId, now));
            return resolvedName;
        }

        Folder copiedRoot = cloneFolder((Folder) source, targetContext.parentId(), targetContext.target(), resolvedName, userId, now);
        copyChildrenRecursively(source.getId(), new FileOperationHelper.CopyTargetContext(copiedRoot.getId(), targetContext.target()), userId, now, childrenMap, batchBytes);
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
        fileObjectReferenceService.retainReference(clone.getUuidName(), source.getStorageProvider());
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

    private void copyChildrenRecursively(Long sourceParentId, FileOperationHelper.CopyTargetContext targetContext, Long userId, LocalDateTime now, Map<Long, List<FileNode>> childrenMap, ByteAccumulator batchBytes) {
        helper.walkDescendantsPreloaded(sourceParentId, childrenMap, targetContext, (child, currentTargetContext) -> {
            String resolvedName = helper.resolveCopyName(child, currentTargetContext, userId);
            if (child instanceof FileItem fileItem) {
                batchBytes.add(cloneFileItem(fileItem, currentTargetContext.parentId(), currentTargetContext.target(), resolvedName, userId, now));
                return currentTargetContext;
            }
            Folder copiedChild = cloneFolder((Folder) child, currentTargetContext.parentId(), currentTargetContext.target(), resolvedName, userId, now);
            return new FileOperationHelper.CopyTargetContext(copiedChild.getId(), currentTargetContext.target());
        });
    }
}
