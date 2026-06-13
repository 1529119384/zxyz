package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileNodeType;
import uno.acloud.common.FileSpaceType;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.util.TransactionUtils;
import uno.acloud.file.vo.BatchOperationDetailVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class FileOperationHelper {

    public static final String ACTION_MOVED = "moved";
    public static final String ACTION_COPIED = "copied";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAIL = "fail";
    public static final String STATUS_SKIPPED = "skipped";
    public static final int MAX_FOLDER_DEPTH = 100;

    private final FileMapper fileMapper;
    private final FileDomainValidator fileDomainValidator;
    private final FileAccessGuard fileAccessGuardService;
    private final FileObjectReferenceManager fileObjectReferenceService;
    private final FileResourceChangedPublisher fileResourceChangedPublisher;

    public FileOperationHelper(FileMapper fileMapper,
                               FileDomainValidator fileDomainValidator,
                               FileAccessGuard fileAccessGuardService,
                               FileObjectReferenceManager fileObjectReferenceService,
                               Optional<FileResourceChangedPublisher> fileResourceChangedPublisher) {
        this.fileMapper = fileMapper;
        this.fileDomainValidator = fileDomainValidator;
        this.fileAccessGuardService = fileAccessGuardService;
        this.fileObjectReferenceService = fileObjectReferenceService;
        this.fileResourceChangedPublisher = fileResourceChangedPublisher.orElse(null);
    }

    // ---- Inner types ----

    @FunctionalInterface
    public interface DescendantAction<T> {
        T apply(FileNode child, T parentContext);
    }

    public record CopyTargetContext(Long parentId, SpaceTarget target, Set<String> reservedFolderNames, Set<String> reservedFileNames) {

        public CopyTargetContext(Long parentId, SpaceTarget target) {
            this(parentId, target, new HashSet<>(), new HashSet<>());
        }

        public Set<String> reservedNamesFor(Integer fileType) {
            return Integer.valueOf(FileNodeType.FOLDER).equals(fileType) ? reservedFolderNames : reservedFileNames;
        }
    }

    public record MoveTargetContext(Long parentId,
                                   SpaceTarget target,
                                   Set<String> reservedFolderNames,
                                   Set<String> reservedFileNames,
                                   List<BatchOperationDetailVO.ItemDetail> details) {

        public MoveTargetContext(Long parentId, SpaceTarget target) {
            this(parentId, target, new HashSet<>(), new HashSet<>(), new ArrayList<>());
        }

        public Set<String> reservedNamesFor(Integer fileType) {
            return Integer.valueOf(FileNodeType.FOLDER).equals(fileType) ? reservedFolderNames : reservedFileNames;
        }

        public void reserve(Integer fileType, String resolvedName) {
            reservedNamesFor(fileType).add(resolvedName);
        }

        public void record(FileNode fileNode,
                           String action,
                           String finalName,
                           boolean renamed,
                           String status,
                           Integer code,
                           String msg) {
            details.add(new BatchOperationDetailVO.ItemDetail(
                    fileNode.getId(),
                    fileNode.getOriginalName(),
                    fileNode.getFileType(),
                    action,
                    renamed,
                    finalName,
                    status,
                    code,
                    msg
            ));
        }

        public boolean hasDetail(Long fileId) {
            return details.stream().anyMatch(detail -> fileId.equals(detail.getFileId()));
        }
    }

    // ---- Batch result helpers ----

    public BatchOperationDetailVO buildBatchResult(List<BatchOperationDetailVO.ItemDetail> details,
                                                    Long targetParentId) {
        int successCount = countByStatus(details, STATUS_SUCCESS);
        int failCount = countByStatus(details, STATUS_FAIL);
        int skippedCount = countByStatus(details, STATUS_SKIPPED);
        int renamedCount = (int) details.stream()
                .filter(detail -> Boolean.TRUE.equals(detail.getRenamed()))
                .count();
        return new BatchOperationDetailVO(
                details.size(),
                successCount,
                failCount,
                skippedCount,
                renamedCount,
                targetParentId,
                details
        );
    }

    public BatchOperationDetailVO.ItemDetail buildDetail(FileNode fileNode,
                                                          String action,
                                                          String finalName,
                                                          boolean renamed,
                                                          String status,
                                                          Integer code,
                                                          String msg) {
        return new BatchOperationDetailVO.ItemDetail(
                fileNode.getId(),
                fileNode.getOriginalName(),
                fileNode.getFileType(),
                action,
                renamed,
                finalName,
                status,
                code,
                msg
        );
    }

    public int countByStatus(List<BatchOperationDetailVO.ItemDetail> details, String status) {
        return (int) details.stream()
                .filter(detail -> status.equals(detail.getStatus()))
                .count();
    }

    public BusinessException withBatchData(BusinessException e, MoveTargetContext targetContext) {
        return withBatchData(e, targetContext.details(), targetContext.parentId());
    }

    public BusinessException withBatchData(BusinessException e,
                                            List<BatchOperationDetailVO.ItemDetail> details,
                                            Long targetParentId) {
        return new BusinessException(e.getErrorCode(), e.getMessage(), buildBatchResult(details, targetParentId));
    }

    // ---- Name comparison ----

    public boolean isRenamed(FileNode fileNode, String resolvedName) {
        return !fileNode.getOriginalName().equals(resolvedName);
    }

    // ---- Recursive walk ----

    public <T> void walkDescendants(Long sourceParentId, T parentContext, DescendantAction<T> action) {
        walkDescendants(sourceParentId, parentContext, action, 0);
    }

    private <T> void walkDescendants(Long sourceParentId, T parentContext, DescendantAction<T> action, int depth) {
        if (depth > MAX_FOLDER_DEPTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件夹层级超过上限 " + MAX_FOLDER_DEPTH + " 层");
        }
        for (FileNode child : getSortedChildren(sourceParentId)) {
            T childContext = action.apply(child, parentContext);
            if (child instanceof Folder) {
                walkDescendants(child.getId(), childContext, action, depth + 1);
            }
        }
    }

    // ---- Preloaded walk (avoids N+1 queries) ----

    public <T> void walkDescendantsPreloaded(Long rootParentId,
                                              Map<Long, List<FileNode>> childrenMap,
                                              T parentContext,
                                              DescendantAction<T> action) {
        walkDescendantsPreloaded(rootParentId, childrenMap, parentContext, action, 0);
    }

    private <T> void walkDescendantsPreloaded(Long parentId,
                                               Map<Long, List<FileNode>> childrenMap,
                                               T parentContext,
                                               DescendantAction<T> action,
                                               int depth) {
        if (depth > MAX_FOLDER_DEPTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件夹层级超过上限 " + MAX_FOLDER_DEPTH + " 层");
        }
        List<FileNode> children = new ArrayList<>(childrenMap.getOrDefault(parentId, List.of()));
        children.sort(Comparator.comparing(FileNode::getId));
        for (FileNode child : children) {
            T childContext = action.apply(child, parentContext);
            if (child instanceof Folder) {
                walkDescendantsPreloaded(child.getId(), childrenMap, childContext, action, depth + 1);
            }
        }
    }

    public Map<Long, List<FileNode>> buildChildrenMap(List<FileNode> nodes) {
        Map<Long, List<FileNode>> map = new HashMap<>();
        for (FileNode node : nodes) {
            map.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
        }
        return map;
    }

    public List<FileNode> getSortedChildren(Long parentId) {
        FileNode parent = fileDomainValidator.requireNode(parentId);
        Long ownerUserId = parent.getTeamId() == null ? parent.getUploadUserId() : null;
        List<FileNode> children = new ArrayList<>(fileMapper.getFileNodesByParentId(parentId, parent.getTeamId(), ownerUserId));
        children.sort(Comparator.comparing(FileNode::getId));
        return children;
    }

    // ---- MQ event publishing ----

    public void publishByIdsAfterCommit(String eventType, List<Long> fileIds) {
        if (fileResourceChangedPublisher == null || fileIds == null || fileIds.isEmpty()) {
            return;
        }
        List<Long> eventFileIds = List.copyOf(fileIds);
        TransactionUtils.runAfterCommit(() -> fileResourceChangedPublisher.publishByIds(eventType, eventFileIds));
    }

    // ---- Space target resolution ----

    public SpaceTarget resolveOperationTarget(Long targetParentId,
                                               Long requestedTeamId,
                                               Integer requestedSpaceType,
                                               Long requestedProjectId,
                                               Folder targetFolder) {
        if (Long.valueOf(-1L).equals(targetParentId)) {
            return SpaceTarget.fromRequest(requestedTeamId, requestedSpaceType, requestedProjectId);
        }
        SpaceTarget target = SpaceTarget.fromNode(targetFolder);
        if (requestedTeamId != null && !requestedTeamId.equals(target.teamId())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "目标目录不属于当前空间");
        }
        if (requestedSpaceType != null && !requestedSpaceType.equals(target.spaceType())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "目标目录不属于当前空间类型");
        }
        if (requestedProjectId != null && !requestedProjectId.equals(target.projectId())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "目标目录不属于当前项目空间");
        }
        return target;
    }

    public void requireTargetWriteAccess(SpaceTarget target, Long userId) {
        if (FileSpaceType.isProject(target.spaceType())) {
            if (target.projectId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId 不能为空");
            }
            fileAccessGuardService.requireProjectFileAccess(target.projectId(), userId);
            return;
        }
        fileAccessGuardService.requireTeamWritePermission(target.teamId(), userId);
    }

    // ---- Name resolution for copy/move ----

    public String resolveCopyName(FileNode source, CopyTargetContext targetContext, Long userId) {
        Integer fileType = source.isFolder() ? FileNodeType.FOLDER : FileNodeType.FILE;
        Set<String> reservedNames = targetContext.reservedNamesFor(fileType);
        String resolvedName = fileDomainValidator.resolveAvailableName(
                targetContext.parentId(),
                targetContext.target(),
                fileType,
                source.getOriginalName(),
                reservedNames,
                targetContext.target().ownerUserId(userId)
        );
        reservedNames.add(resolvedName);
        return resolvedName;
    }

    public String resolveMoveName(FileNode source, MoveTargetContext targetContext, Long userId) {
        Integer fileType = source.isFolder() ? FileNodeType.FOLDER : FileNodeType.FILE;
        return fileDomainValidator.resolveAvailableName(
                targetContext.parentId(),
                targetContext.target(),
                fileType,
                source.getOriginalName(),
                targetContext.reservedNamesFor(fileType),
                targetContext.target().ownerUserId(userId)
        );
    }
}
