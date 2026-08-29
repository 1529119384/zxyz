package uno.acloud.file.service.impl;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.FileNodeType;
import uno.acloud.common.FileSpaceType;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.service.FileFolderPort;
import uno.acloud.file.vo.FolderCreateResultVO;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Service
public class FileFolderService implements FileFolderPort {

    private static final int MAX_NAME_RETRY_ATTEMPTS = 64;

    private final FileMapper fileMapper;
    private final FilePathResolver filePathResolver;
    private final FileDomainValidator fileDomainValidator;
    private final FileAccessGuard fileAccessGuardService;

    public FileFolderService(FileMapper fileMapper, FilePathResolver filePathResolver,
                             FileDomainValidator fileDomainValidator, FileAccessGuard fileAccessGuardService) {
        this.fileMapper = fileMapper;
        this.filePathResolver = filePathResolver;
        this.fileDomainValidator = fileDomainValidator;
        this.fileAccessGuardService = fileAccessGuardService;
    }

    @Override
    public FolderCreateResultVO createFolder(String folderName, Long parentId, Long requestedTeamId, Long userId) {
        return createFolder(folderName, parentId, requestedTeamId, null, null, userId);
    }

    @Override
    public FolderCreateResultVO createFolder(String folderName, Long parentId, Long requestedTeamId, Integer requestedSpaceType, Long requestedProjectId, Long userId) {
        SpaceTarget target = resolveFolderTarget(parentId, requestedTeamId, requestedSpaceType, requestedProjectId, userId);
        requireWriteAccess(target, userId);
        Set<String> reservedNames = new HashSet<>();
        for (int attempt = 0; ; attempt++) {
            String finalFolderName = fileDomainValidator.resolveAvailableName(
                    parentId,
                    target,
                    FileNodeType.FOLDER,
                    folderName,
                    reservedNames,
                    target.ownerUserId(userId)
            );
            reservedNames.add(finalFolderName);

            try {
                Folder folder = buildFolder(finalFolderName, parentId, target, userId);
                Integer result = fileMapper.insertFolder(folder);
                if (result == null || result == 0) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "创建文件夹失败");
                }
                return new FolderCreateResultVO(folder.getId(), finalFolderName, folder.getFileType(), folder.getParentId());
            } catch (DuplicateKeyException e) {
                if (attempt >= MAX_NAME_RETRY_ATTEMPTS - 1) {
                    throw e;
                }
                // 并发下同名被先提交者占用，重试下一个序号名（name, name(1), name(2)…）
            }
        }
    }

    private Folder buildFolder(String finalFolderName, Long parentId, SpaceTarget target, Long userId) {
        Folder folder = Folder.create();
        folder.setOriginalName(finalFolderName);
        folder.setStorePath(filePathResolver.buildStorePath(parentId, finalFolderName));
        folder.setUploadUserId(userId);
        folder.setTeamId(target.teamId());
        folder.setSpaceType(target.spaceType());
        folder.setProjectId(target.projectId());
        folder.setParentId(parentId);
        folder.setCreateTime(LocalDateTime.now());
        folder.setModifyTime(LocalDateTime.now());
        folder.setDeleted(FileDeleteStatus.NORMAL);
        return folder;
    }

    private SpaceTarget resolveFolderTarget(Long parentId, Long requestedTeamId, Integer requestedSpaceType, Long requestedProjectId, Long userId) {
        if (parentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "parentId 不能为空");
        }
        if (Long.valueOf(-1L).equals(parentId)) {
            return SpaceTarget.fromRequest(requestedTeamId, requestedSpaceType, requestedProjectId);
        }
        Folder parentFolder = fileDomainValidator.requireFolder(parentId);
        fileAccessGuardService.requireWriteAccess(parentFolder, userId);
        if (requestedTeamId != null && !requestedTeamId.equals(parentFolder.getTeamId())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "父级目录不属于当前空间");
        }
        if (requestedProjectId != null && !requestedProjectId.equals(parentFolder.getProjectId())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "父级目录不属于当前项目空间");
        }
        return SpaceTarget.fromNode(parentFolder);
    }

    private void requireWriteAccess(SpaceTarget target, Long userId) {
        if (FileSpaceType.isProject(target.spaceType())) {
            if (target.projectId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId 不能为空");
            }
            fileAccessGuardService.requireProjectFileAccess(target.projectId(), userId);
            return;
        }
        fileAccessGuardService.requireTeamWritePermission(target.teamId(), userId);
    }
}
