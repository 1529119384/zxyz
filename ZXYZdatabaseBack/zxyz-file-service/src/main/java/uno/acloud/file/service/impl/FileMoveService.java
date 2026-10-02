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
    /** 单次移动的顶层节点上限（F10）。复用 copy 的配置键，见 {@link #requireWithinMoveLimit}。 */
    private final int maxNodesPerTransaction;

    public FileMoveService(FileMapper fileMapper,
                           FileDomainValidator fileDomainValidator,
                           FilePathResolver filePathResolver,
                           FileAccessGuard fileAccessGuardService,
                           FileOperationHelper helper,
                           TransactionHelper transactionHelper,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${app.file.copy.max-nodes-per-tx:500}") int maxNodesPerTransaction) {
        this.fileMapper = fileMapper;
        this.fileDomainValidator = fileDomainValidator;
        this.filePathResolver = filePathResolver;
        this.fileAccessGuardService = fileAccessGuardService;
        this.helper = helper;
        this.transactionHelper = transactionHelper;
        this.maxNodesPerTransaction = maxNodesPerTransaction;
    }

    public BatchOperationDetailVO moveFiles(List<Long> fileIds, Long targetParentId, Long requestedTeamId, Long userId) {
        return moveFiles(fileIds, targetParentId, requestedTeamId, null, null, userId);
    }

    public BatchOperationDetailVO moveFiles(List<Long> fileIds, Long targetParentId, Long requestedTeamId, Integer requestedSpaceType, Long requestedProjectId, Long userId) {
        fileDomainValidator.validateUserId(userId);
        fileDomainValidator.validateTargetParentId(targetParentId);
        requireWithinMoveLimit(fileIds);

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
        // F12：移动可能跨空间（团队 ↔ 项目），源与目标的 scope_key 都会变 ⇒ 两个 scope 都要失效。
        // 源 scope 取自被移动的顶层节点（它们记录着原归属），目标 scope 取自已解析的 target。
        List<FileOperationHelper.ScopeRef> affectedScopes = new java.util.ArrayList<>();
        affectedScopes.add(FileOperationHelper.ScopeRef.fromTarget(target, userId));
        for (FileNode node : topLevelNodes) {
            affectedScopes.add(FileOperationHelper.ScopeRef.fromNode(node));
        }
        helper.publishByIdsAfterCommit("MOVED", fileIds, affectedScopes);
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
                String oldStorePath = fileNode.getStorePath();
                updateDescendantStorePaths(fileNode.getId(), newStorePath, targetContext.target(), childrenMap);
                // F18：回收站内的后代不在上面的遍历范围内（CTE 限定 deleted = 0），
                // 必须在活跃后代改完之后再用前缀 UPDATE 补齐（见方法注释的调用顺序说明）。
                updateRecycledDescendantStorePaths(oldStorePath, newStorePath);
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

    /**
     * F18（P3 数据一致性）：把<b>回收站内</b>后代的 store_path 一并改写为新前缀。
     *
     * <h2>修复前的缺陷</h2>
     * <p>{@link #updateDescendantStorePaths} 遍历的是 {@code collectDescendantNodes} 的结果，
     * 而该 CTE 的种子与递归步都限定 {@code deleted = 0} ⇒ <b>回收站里的后代完全不被改写</b>，
     * 它们的 store_path 停留在移动前的旧前缀。</p>
     * <p>后果不是立刻可见的：等用户把那些子节点从回收站还原时，
     * {@code FileLifecycleService#buildRenamedStorePath} 会以<b>旧</b> store_path 重建路径，
     * 于是还原后的节点路径与祖先链不一致。而 {@code reduceToTopLevelNodes}、
     * {@code validateFolderTarget} 等逻辑都按<b>前缀</b>判断从属关系 ⇒ 这些节点会被误判
     * （例如「不能把文件夹移动到自身子目录」的守卫会失效）。</p>
     *
     * <h2>为什么复用 {@code renameDescendantStorePaths}（而不是新增 SQL）</h2>
     * <p>该语句的 WHERE 已经是 {@code store_path LIKE CONCAT(oldPrefix,'/%') AND deleted IN (0, 1)}
     * —— 恰好覆盖「活跃 + 回收站」两种后代，正是 F18 需要的口径；且它已在
     * {@code FileRenameService#renameFolderTree} 的目录改名路径上用了很久。</p>
     *
     * <h2>⚠️ 为什么必须在上面的遍历<b>之后</b>调用</h2>
     * <p>上面的遍历已把活跃后代改写成新前缀，它们<b>不再匹配</b> {@code oldPrefix/%}；
     * 因此这条前缀 UPDATE 实际只会命中「仍停留在旧前缀的回收站后代」——
     * 既补齐了缺失的一类，又不会重复改写活跃节点（无需额外条件区分）。</p>
     *
     * <p><b>为什么不干脆去掉上面的遍历、只留这一条</b>：前缀 UPDATE 只改 {@code store_path}，
     * 不会更新 {@code team_id}/{@code space_type}/{@code project_id}；而跨空间移动时
     * 后代的这三个列也必须跟着走，否则容量口径与权限判断会错位。故两者职责不同、必须并存。</p>
     */
    private void updateRecycledDescendantStorePaths(String oldPrefix, String newPrefix) {
        if (oldPrefix == null || oldPrefix.isBlank() || newPrefix == null || newPrefix.isBlank()
                || oldPrefix.equals(newPrefix)) {
            return;
        }
        fileMapper.renameDescendantStorePaths(oldPrefix, newPrefix);
    }

    private boolean isSameOperationTarget(FileNode fileNode, FileOperationHelper.MoveTargetContext targetContext) {
        return fileDomainValidator.isSameParent(fileNode, targetContext.parentId())
                && SpaceTarget.fromNode(fileNode).equals(targetContext.target());
    }

    /**
     * F10（P3 无界输入）：单次移动的顶层 fileIds 数量上限。
     *
     * <h2>为什么需要</h2>
     * <p>复制路径早有 {@code app.file.copy.max-nodes-per-tx}（默认 500）护栏，移动路径<b>完全没有</b>：
     * {@code collectDescendantNodes} 会把整棵子树<b>全量预载入内存</b>，随后在<b>单个事务</b>内
     * 逐行 UPDATE 每个后代（{@code FileMoveService.java:145-157}）。一个「移动大目录」的请求
     * 就等于长事务 + 大结果集，占着 DB 连接并放大锁范围。</p>
     *
     * <h2>为什么与 copy 用同一个配置键</h2>
     * <p>两者都是「一个请求承载一棵子树、单事务逐行改写」的同型操作，成本模型一致；
     * 分设两个键会让运维必须记住两处、并让「只调了 copy 没调 move」成为静默的不一致。
     * 复用 {@code app.file.copy.max-nodes-per-tx} 也保证两边的护栏不会漂移。</p>
     *
     * <p>注意这里只限制<b>顶层 fileIds 条数</b>（与 copy 的语义一致：copy 计的是
     * topLevel + descendants，move 在预载后代之前无法确知后代总数，
     * 故以顶层条数作前置快速失败；真正的子树规模由 copy 那边同一预算量级约束）。</p>
     */
    private void requireWithinMoveLimit(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        if (fileIds.size() > maxNodesPerTransaction) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "单次移动文件数量过多（" + fileIds.size() + "），请分批操作（上限 "
                            + maxNodesPerTransaction + "）");
        }
    }
}
