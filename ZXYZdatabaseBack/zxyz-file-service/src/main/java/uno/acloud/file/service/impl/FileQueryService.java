package uno.acloud.file.service.impl;

import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.FileSpaceType;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.file.storage.DownloadInfo;
import uno.acloud.vo.FileDownloadUrlVO;
import uno.acloud.file.vo.FileListItemVO;
import uno.acloud.file.vo.FileListPagedResultVO;
import uno.acloud.file.vo.FileResourceVO;
import uno.acloud.file.vo.FileSearchItemVO;
import uno.acloud.file.vo.FileSearchResultVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FileQueryService implements FileQueryPort {

    private static final SortField DEFAULT_SORT_FIELD = SortField.NAME;
    private static final SortOrder DEFAULT_SORT_ORDER = SortOrder.ASC;

    private final FileMapper fileMapper;
    private final StorageProviderRegistry registry;
    private final FileDomainValidator fileDomainValidator;
    private final FileConverter fileConverter;
    private final FileAccessGuard fileAccessGuardService;

    public FileQueryService(FileMapper fileMapper, StorageProviderRegistry registry, FileDomainValidator fileDomainValidator,
                            FileConverter fileConverter, FileAccessGuard fileAccessGuardService) {
        this.fileMapper = fileMapper;
        this.registry = registry;
        this.fileDomainValidator = fileDomainValidator;
        this.fileConverter = fileConverter;
        this.fileAccessGuardService = fileAccessGuardService;
    }

    @Override
    public List<FileListItemVO> getFileListByParentId(Long parentId, Long teamId, String sortField, String sortOrder, Long userId) {
        return getFileListByParentId(parentId, teamId, null, null, sortField, sortOrder, userId);
    }

    @Override
    public List<FileListItemVO> getFileListByParentId(Long parentId, Long teamId, Integer spaceType, Long projectId, String sortField, String sortOrder, Long userId) {
        if (parentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "parentId 不能为空");
        }
        SpaceTarget target = resolveListTarget(parentId, teamId, spaceType, projectId, userId);
        requireReadAccess(target, userId);
        SortOption sortOption = resolveSortOption(sortField, sortOrder);
        List<FileListItemVO> fileList = fileMapper.getFileNodesByParentId(parentId, target.teamId(), target.spaceType(), target.projectId(), userId)
                .stream()
                .map(fileConverter::toFileListItemVO)
                .collect(Collectors.toList());
        fileList.sort(buildFileListComparator(sortOption));
        return fileList;
    }

    @Override
    public FileListPagedResultVO getFileListByParentId(Long parentId, Long teamId, Integer spaceType, Long projectId, String sortField, String sortOrder, Integer page, Integer pageSize, Long userId) {
        if (parentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "parentId 不能为空");
        }
        SpaceTarget target = resolveListTarget(parentId, teamId, spaceType, projectId, userId);
        requireReadAccess(target, userId);
        SortOption sortOption = resolveSortOption(sortField, sortOrder);
        int finalPage = page == null || page < 1 ? 1 : page;
        int finalPageSize = pageSize == null || pageSize < 1 ? 50 : Math.min(pageSize, 100);
        int offset = (finalPage - 1) * finalPageSize;

        long total = fileMapper.countByParentId(parentId, target.teamId(), target.spaceType(), target.projectId(), userId);
        List<FileListItemVO> fileList = total == 0
                ? new ArrayList<>()
                : fileMapper.getFileNodesByParentIdPaged(parentId, target.teamId(), target.spaceType(), target.projectId(), userId, finalPageSize, offset)
                        .stream()
                        .map(fileConverter::toFileListItemVO)
                        .sorted(buildFileListComparator(sortOption))
                        .collect(Collectors.toList());
        return new FileListPagedResultVO(finalPage, finalPageSize, total, fileList);
    }

    @Override
    public FileDownloadUrlVO getFileDownloadUrl(Long fileId, Long userId) {
        if (fileId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileId 不能为空");
        }

        FileItem fileItem = fileDomainValidator.requireFileItem(fileId);
        fileAccessGuardService.requireReadAccess(fileItem, userId);
        if (!Integer.valueOf(FileDeleteStatus.NORMAL).equals(fileItem.getDeleted())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "文件当前状态不允许下载");
        }
        if (fileItem.getUuidName() == null || fileItem.getUuidName().isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件资源不存在");
        }
        StorageProvider provider = registry.resolveForFile(fileItem);
        if (!provider.objectExists(fileItem.getUuidName())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件资源已丢失，请联系管理员");
        }
        DownloadInfo downloadInfo = provider.generateDownloadInfo(fileItem.getUuidName(), fileItem.getOriginalName());
        return new FileDownloadUrlVO(fileId, downloadInfo.getDownloadUrl(),
                downloadInfo.isDirectDownload(), fileItem.getOriginalName());
    }

    @Override
    public FileDownloadUrlVO getFileDownloadUrl(Long fileId) {
        return getFileDownloadUrl(fileId, null);
    }

    @Override
    public FileDownloadUrlVO getSharedFileDownloadUrl(Long fileId) {
        if (fileId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileId 不能为空");
        }
        FileItem fileItem = fileDomainValidator.requireFileItem(fileId);
        if (!Integer.valueOf(FileDeleteStatus.NORMAL).equals(fileItem.getDeleted())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "文件当前状态不允许下载");
        }
        if (fileItem.getUuidName() == null || fileItem.getUuidName().isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件资源不存在");
        }
        StorageProvider provider = registry.resolveForFile(fileItem);
        if (!provider.objectExists(fileItem.getUuidName())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件资源已丢失，请联系管理员");
        }
        DownloadInfo downloadInfo = provider.generateDownloadInfo(fileItem.getUuidName(), fileItem.getOriginalName());
        return new FileDownloadUrlVO(fileId, downloadInfo.getDownloadUrl(),
                downloadInfo.isDirectDownload(), fileItem.getOriginalName());
    }

    @Override
    public List<FileListItemVO> getRecycleList(Long teamId, Long userId) {
        return getRecycleList(teamId, null, null, userId);
    }

    @Override
    public List<FileListItemVO> getRecycleList(Long teamId, Integer spaceType, Long projectId, Long userId) {
        SpaceTarget target = SpaceTarget.fromRequest(teamId, spaceType, projectId);
        requireReadAccess(target, userId);
        List<FileNode> recycleNodes = projectId == null
                ? fileMapper.getFileNodesInRecycleBin(target.teamId(), userId)
                : fileMapper.getFileNodesInRecycleBin(target.teamId(), target.spaceType(), target.projectId(), userId);
        return recycleNodes
                .stream()
                .map(fileConverter::toFileListItemVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<FileInfoDTO> getFileInfoByIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        return fileMapper.getFileNodesByIds(fileIds).stream()
                .map(fileConverter::toFileInfoDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<FileInfoDTO> getFileInfoByIds(List<Long> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        List<FileNode> nodes = fileMapper.getFileNodesByIds(fileIds);
        fileAccessGuardService.requireReadAccess(nodes, userId);
        return nodes.stream()
                .map(fileConverter::toFileInfoDTO)
                .collect(Collectors.toList());
    }

    @Override
    public FileInfoDTO getFileInfoById(Long fileId) {
        return fileConverter.toFileInfoDTO(fileMapper.getActiveFileNodeById(fileId));
    }

    @Override
    public FileInfoDTO getFileInfoById(Long fileId, Long userId) {
        return fileConverter.toFileInfoDTO(fileDomainValidator.requireNode(fileId, userId, fileAccessGuardService));
    }

    @Override
    public List<FileInfoDTO> getChildrenByParentIdWithDeleted(Long parentId) {
        if (parentId == null) {
            return List.of();
        }
        FileNode parent = fileDomainValidator.requireNode(parentId);
        return fileMapper.getChildrenByParentIdWithDeleted(parentId, parent.getTeamId(), null).stream()
                .map(fileConverter::toFileInfoDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<FileInfoDTO> getChildrenByParentIdWithDeleted(Long parentId, Long userId) {
        if (parentId == null) {
            return List.of();
        }
        FileNode parent = fileDomainValidator.requireNode(parentId, userId, fileAccessGuardService);
        Long ownerUserId = parent.getTeamId() == null ? parent.getUploadUserId() : null;
        return fileMapper.getChildrenByParentIdWithDeleted(parentId, parent.getTeamId(), ownerUserId).stream()
                .map(fileConverter::toFileInfoDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<FileInfoDTO> getShareChildrenByParentIdWithDeleted(Long parentId) {
        if (parentId == null) {
            return List.of();
        }
        fileDomainValidator.requireNode(parentId);
        return fileMapper.getShareChildrenByParentIdWithDeleted(parentId).stream()
                .map(fileConverter::toFileInfoDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Map<Long, List<FileInfoDTO>> getShareChildrenByParentIdsWithDeleted(List<Long> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Map.of();
        }
        return fileMapper.getShareChildrenByParentIdsWithDeleted(parentIds).stream()
                .map(fileConverter::toFileInfoDTO)
                .collect(Collectors.groupingBy(FileInfoDTO::getParentId));
    }

    @Override
    public FileResourceVO getFileResourceById(Long fileId) {
        return fileConverter.toFileResourceVO(fileMapper.getActiveFileNodeById(fileId));
    }

    @Override
    public FileResourceVO getFileResourceById(Long fileId, Long userId) {
        return fileConverter.toFileResourceVO(fileDomainValidator.requireNode(fileId, userId, fileAccessGuardService));
    }

    @Override
    public FileSearchResultVO searchFiles(String keyword, Integer page, Integer pageSize, long userId, Long teamId) {
        return searchFiles(keyword, page, pageSize, userId, teamId, null, null);
    }

    @Override
    public FileSearchResultVO searchFiles(String keyword, Integer page, Integer pageSize, long userId, Long teamId, Integer spaceType, Long projectId) {
        fileAccessGuardService.requireTeamViewPermission(teamId, userId);
        if (FileSpaceType.isProject(FileSpaceType.normalize(spaceType, teamId, projectId))) {
            fileAccessGuardService.requireProjectFileAccess(projectId, userId);
        }
        String normalizedKeyword = normalizeKeyword(keyword);
        int finalPage = page == null || page < 1 ? 1 : page;
        int finalPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 50);
        int offset = (finalPage - 1) * finalPageSize;

        long total = fileMapper.countByKeyword(userId, teamId, normalizedKeyword);
        List<FileSearchItemVO> searchItems = total == 0
                ? new ArrayList<>()
                : fileMapper.searchByKeyword(userId, teamId, normalizedKeyword, finalPageSize, offset);
        return new FileSearchResultVO(total, searchItems);
    }

    @Override
    public FileNode getFileNodeById(Long fileId) {
        return fileMapper.getActiveFileNodeById(fileId);
    }

    @Override
    public FileNode getFileNodeForStream(Long fileId, Long userId) {
        return fileDomainValidator.requireNode(fileId, userId, fileAccessGuardService);
    }

    public FileSearchResultVO searchFiles(String keyword, Integer page, Integer pageSize, long userId) {
        return searchFiles(keyword, page, pageSize, userId, null);
    }

    private SpaceTarget resolveListTarget(Long parentId, Long requestedTeamId, Integer requestedSpaceType, Long requestedProjectId, Long userId) {
        if (Long.valueOf(-1L).equals(parentId)) {
            return SpaceTarget.fromRequest(requestedTeamId, requestedSpaceType, requestedProjectId);
        }
        FileNode parent = fileDomainValidator.requireNode(parentId, userId, fileAccessGuardService);
        if (requestedTeamId != null && !requestedTeamId.equals(parent.getTeamId())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "目录不属于当前空间");
        }
        if (requestedProjectId != null && !requestedProjectId.equals(parent.getProjectId())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "目录不属于当前项目空间");
        }
        return SpaceTarget.fromNode(parent);
    }

    private void requireReadAccess(SpaceTarget target, Long userId) {
        if (FileSpaceType.isProject(target.spaceType())) {
            if (target.projectId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId 不能为空");
            }
            fileAccessGuardService.requireProjectFileAccess(target.projectId(), userId);
            return;
        }
        // 个人空间无需团队权限校验，文件级访问控制由 FileAccessGuard 的 owner 校验保证
        if (target.teamId() == null) {
            return;
        }
        fileAccessGuardService.requireTeamViewPermission(target.teamId(), userId);
    }

    private SortOption resolveSortOption(String sortField, String sortOrder) {
        boolean hasSortField = sortField != null && !sortField.isBlank();
        boolean hasSortOrder = sortOrder != null && !sortOrder.isBlank();
        if (!hasSortField && !hasSortOrder) {
            return new SortOption(DEFAULT_SORT_FIELD, DEFAULT_SORT_ORDER);
        }
        if (!hasSortField || !hasSortOrder) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "sortField 和 sortOrder 必须同时传入");
        }
        return new SortOption(SortField.from(sortField), SortOrder.from(sortOrder));
    }

    private Comparator<FileListItemVO> buildFileListComparator(SortOption sortOption) {
        Comparator<FileListItemVO> typeComparator = Comparator.comparingInt(file -> folderFirstWeight(file.getFileType()));
        Comparator<FileListItemVO> fieldComparator = buildSortFieldComparator(sortOption);
        return typeComparator.thenComparing(fieldComparator);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.SEARCH_KEYWORD_EMPTY, "搜索关键字不能为空");
        }
        return keyword.trim();
    }

    private Comparator<FileListItemVO> buildSortFieldComparator(SortOption sortOption) {
        return switch (sortOption.sortField()) {
            case NAME -> buildNameComparator(sortOption.sortOrder());
            case MODIFY_TIME -> buildModifyTimeComparator(sortOption.sortOrder());
            case SIZE -> buildSizeComparator(sortOption.sortOrder());
        };
    }

    private Comparator<FileListItemVO> buildNameComparator(SortOrder sortOrder) {
        Comparator<String> nameValueComparator = Comparator.nullsFirst(String::compareTo);
        Comparator<FileListItemVO> comparator = Comparator.comparing(FileListItemVO::getOriginalName, nameValueComparator);
        return applyOrder(comparator, sortOrder);
    }

    private Comparator<FileListItemVO> buildModifyTimeComparator(SortOrder sortOrder) {
        Comparator<LocalDateTime> timeValueComparator = Comparator.nullsFirst(LocalDateTime::compareTo);
        Comparator<FileListItemVO> comparator = Comparator.comparing(FileListItemVO::getModifyTime, timeValueComparator);
        return applyOrder(comparator, sortOrder);
    }

    private Comparator<FileListItemVO> buildSizeComparator(SortOrder sortOrder) {
        return (left, right) -> compareSize(left.getFileSize(), right.getFileSize(), sortOrder);
    }

    private <T> Comparator<T> applyOrder(Comparator<T> comparator, SortOrder sortOrder) {
        return sortOrder == SortOrder.ASC ? comparator : comparator.reversed();
    }

    private int folderFirstWeight(Integer fileType) {
        return Integer.valueOf(0).equals(fileType) ? 0 : 1;
    }

    private int compareSize(Long leftSize, Long rightSize, SortOrder sortOrder) {
        if (leftSize == null && rightSize == null) {
            return 0;
        }
        if (leftSize == null) {
            return sortOrder == SortOrder.ASC ? -1 : 1;
        }
        if (rightSize == null) {
            return sortOrder == SortOrder.ASC ? 1 : -1;
        }
        return sortOrder == SortOrder.ASC ? Long.compare(leftSize, rightSize) : Long.compare(rightSize, leftSize);
    }

    private record SortOption(SortField sortField, SortOrder sortOrder) {
    }

    private enum SortField {
        NAME,
        MODIFY_TIME,
        SIZE;

        private static SortField from(String rawValue) {
            return switch (rawValue.trim()) {
                case "name" -> NAME;
                case "modifyTime" -> MODIFY_TIME;
                case "size" -> SIZE;
                default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "非法的 sortField");
            };
        }
    }

    private enum SortOrder {
        ASC,
        DESC;

        private static SortOrder from(String rawValue) {
            String normalizedValue = rawValue.trim().toLowerCase(Locale.ROOT);
            return switch (normalizedValue) {
                case "asc" -> ASC;
                case "desc" -> DESC;
                default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "非法的 sortOrder");
            };
        }
    }
}
