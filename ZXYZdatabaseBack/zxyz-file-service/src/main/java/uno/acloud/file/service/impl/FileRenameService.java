package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileNodeType;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.dto.RenameFileRequest;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.file.vo.RenameFileVO;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class FileRenameService {

    private final FileMapper fileMapper;
    private final StorageProviderRegistry registry;
    private final FileDomainValidator fileDomainValidator;
    private final FilePathResolver filePathResolver;
    private final FileAccessGuard fileAccessGuardService;
    private final FileOperationHelper helper;
    private final TransactionTemplate transactionTemplate;
    private final FileResourceChangedPublisher fileResourceChangedPublisher;

    public FileRenameService(FileMapper fileMapper,
                             StorageProviderRegistry registry,
                             FileDomainValidator fileDomainValidator,
                             FilePathResolver filePathResolver,
                             FileAccessGuard fileAccessGuardService,
                             FileOperationHelper helper,
                             TransactionTemplate transactionTemplate,
                             Optional<FileResourceChangedPublisher> fileResourceChangedPublisher) {
        this.fileMapper = fileMapper;
        this.registry = registry;
        this.fileDomainValidator = fileDomainValidator;
        this.filePathResolver = filePathResolver;
        this.fileAccessGuardService = fileAccessGuardService;
        this.helper = helper;
        this.transactionTemplate = transactionTemplate;
        this.fileResourceChangedPublisher = fileResourceChangedPublisher.orElse(null);
    }

    public RenameFileVO renameFile(RenameFileRequest request) {
        return renameFile(request, null);
    }

    public RenameFileVO renameFile(RenameFileRequest request, Long userId) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求不能为空");
        }
        if (request.getFileId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileId 不能为空");
        }

        // 事务外：参数校验 + 加载节点 + 远程权限校验（team-service HTTP 调用），
        // 避免在 DB 事务内发起远程调用导致连接池被长时间占用、高并发雪崩。
        String normalizedNewName = validateRenameName(request.getNewName());
        FileNode fileNode = fileDomainValidator.requireNodeForRename(request.getFileId());
        fileAccessGuardService.requireWriteAccess(fileNode, userId);
        requireNoActiveNameConflict(fileNode, normalizedNewName);

        // 事务内：仅本地 DB 写操作。renameFileNode 内部会在事务提交后（afterCommit）更新 content-disposition，
        // helper.publishByIdsAfterCommit 同样依赖事务内的 afterCommit 注册，因此写入动作必须包裹在此事务中。
        return transactionTemplate.execute(status -> {
            String finalOriginalName = buildRenamedOriginalName(fileNode, normalizedNewName);
            validateFinalOriginalName(finalOriginalName);
            String newStorePath = filePathResolver.buildStorePath(fileNode.getParentId(), finalOriginalName);

            if (fileNode instanceof FileItem fileItem) {
                renameFileNode(fileItem, finalOriginalName, newStorePath);
            } else if (fileNode instanceof Folder folder) {
                renameFolderTree(folder, finalOriginalName, newStorePath);
            } else {
                throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "非法的文件节点类型");
            }

            RenameFileVO response = new RenameFileVO(
                    fileNode.getId(),
                    finalOriginalName,
                    fileNode.getFileType(),
                    fileNode.getParentId(),
                    LocalDateTime.now()
            );
            // F12：重命名不改变节点的空间归属 ⇒ 只失效该节点所在 scope。
            // 节点是事务外加载的（fileNode），其空间字段就是权威来源。
            helper.publishByIdsAfterCommit("RENAMED", List.of(fileNode.getId()),
                    List.of(FileOperationHelper.ScopeRef.fromNode(fileNode)));
            return response;
        });
    }

    private String validateRenameName(String newName) {
        return fileDomainValidator.validateInputName(newName);
    }

    /**
     * F6（P2 异常处理/一致性）：重命名前的同级重名守卫。
     *
     * <h2>为什么需要（修复前的形态）</h2>
     * <p>上传/复制/恢复三条路径都有「撞名自动加序号」的策略（{@code resolveAvailableName}），
     * 唯独重命名没有：用户把文件改成同级已存在的名字时，唯一索引
     * {@code uk_file_node_scope_parent_type_active} 的 {@code DuplicateKeyException} 会直接冒泡，
     * 用户收到全局兜底的 500 + 原始 DB 异常文本 —— 既不可读，也与其余三条路径的行为不一致。</p>
     *
     * <h2>为什么判定要包含「最终文件名」而非用户输入</h2>
     * <p>{@link #buildRenamedOriginalName} 会为用户输入<b>补回原扩展名</b>
     * （输入 {@code report} → 实际落库 {@code report.pdf}）。若只拿用户输入去查重名，
     * 就会漏掉「输入 report 撞上已存在的 report.pdf」这类冲突，守卫形同虚设。
     * 因此这里复用同一套「最终名」推导逻辑再查。</p>
     *
     * <h2>为什么不自动加序号</h2>
     * <p>与上传/复制/恢复不同：重命名是用户<b>显式指定</b>目标名的操作，静默改成
     * {@code name(1)} 会让用户以为改名失败或改错。故此处明确拒绝并给出可读原因，
     * 由用户自行决定换名还是先处理同名项。</p>
     *
     * <h2>为什么必须排除自己</h2>
     * <p>「只改大小写」或「点保存但名字没变」时，查重名会命中<b>自己那一行</b>。
     * 若不排除，这类无害操作会被误判成冲突而拒绝。</p>
     */
    private void requireNoActiveNameConflict(FileNode fileNode, String normalizedNewName) {
        String finalOriginalName = buildRenamedOriginalName(fileNode, normalizedNewName);
        validateFinalOriginalName(finalOriginalName);

        // 名字与现状完全一致（含仅大小写不同）⇒ 无变化，不必查重名。
        // 这一条同时避免了「点保存但没改名」被自己那一行判成冲突。
        if (finalOriginalName.equalsIgnoreCase(fileNode.getOriginalName())) {
            return;
        }

        Integer fileType = fileNode.isFolder() ? FileNodeType.FOLDER : FileNodeType.FILE;
        SpaceTarget target = SpaceTarget.fromNode(fileNode);
        Long ownerUserId = target.ownerUserId(fileNode.getUploadUserId());

        List<String> activeNames = fileMapper.getActiveNamesByParentIdAndFileType(
                fileNode.getParentId(),
                target.teamId(),
                target.spaceType(),
                target.projectId(),
                fileType,
                ownerUserId
        );
        // ⚠️ 必须用 equalsIgnoreCase 而不是 equals：file_node 的排序规则是 utf8mb4_unicode_ci
        //    （大小写不敏感），唯一索引 uk_file_node_scope_parent_type_active 的冲突判定同样是
        //    大小写不敏感的。若此处按大小写敏感比对，用户输入 "Report.pdf" 撞上已存在的 "report.pdf"
        //    就会被放行，最终仍以 DuplicateKeyException 冒泡成 500 —— 守卫形同虚设。
        boolean conflict = activeNames != null && activeNames.stream()
                .anyMatch(name -> name != null && name.equalsIgnoreCase(finalOriginalName));
        if (conflict) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "同名文件/文件夹已存在");
        }
    }

    private void validateFinalOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能为空");
        }
        if (originalName.length() > 255) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名长度不能超过 255");
        }
    }

    private String buildRenamedOriginalName(FileNode fileNode, String newName) {
        if (!(fileNode instanceof FileItem fileItem)) {
            return newName;
        }
        String extension = extractStandardExtension(fileItem.getOriginalName());
        if (extension.isEmpty()) {
            return newName;
        }
        return newName + "." + extension;
    }

    private String extractStandardExtension(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "";
        }
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == originalName.length() - 1) {
            return "";
        }
        return originalName.substring(dotIndex + 1);
    }

    private void renameFileNode(FileItem fileItem, String finalOriginalName, String newStorePath) {
        int updatedRows = fileMapper.renameNodeById(fileItem.getId(), finalOriginalName, newStorePath);
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "重命名文件失败");
        }
        String uuidName = fileItem.getUuidName();
        StorageProvider provider = registry.resolveForFile(fileItem);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        provider.updateContentDisposition(uuidName, finalOriginalName);
                    } catch (Exception e) {
                        log.warn("Failed to update content disposition after rename for uuidName={}: {}", uuidName, e.getMessage());
                    }
                }
            });
        } else {
            provider.updateContentDisposition(uuidName, finalOriginalName);
        }
    }

    private void renameFolderTree(Folder folder, String finalOriginalName, String newStorePath) {
        String oldStorePath = folder.getStorePath();
        int updatedRows = fileMapper.renameNodeById(folder.getId(), finalOriginalName, newStorePath);
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "重命名文件夹失败");
        }
        fileMapper.renameDescendantStorePaths(oldStorePath, newStorePath);
    }
}
