package uno.acloud.file.service.impl;

import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.file.dto.im.FileCardEntryRequest;
import uno.acloud.file.dto.im.FileCardResolveRequest;
import uno.acloud.file.dto.im.FileCardSnapshotRequest;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.service.ImFileCardPort;
import uno.acloud.vo.FileDownloadUrlVO;
import uno.acloud.file.vo.im.FileCardArchiveEntryVO;
import uno.acloud.file.vo.im.FileCardEntryVO;
import uno.acloud.file.vo.im.FileCardResolveVO;
import uno.acloud.file.vo.im.FileCardSnapshotVO;

import org.springframework.lang.Nullable;
import uno.acloud.satoken.AuthServicePort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ImFileCardService implements ImFileCardPort {

    private static final String SHARE_TYPE_SINGLE_FILE = "SINGLE_FILE";
    private static final String SHARE_TYPE_SINGLE_FOLDER = "SINGLE_FOLDER";
    private static final String SHARE_TYPE_MULTI_FILE = "MULTI_FILE";
    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_MOVED = "MOVED";
    private static final String STATUS_DELETED = "DELETED";

    /**
     * F9（P3 N+1 / 无界递归）：单次归档条目上限。
     *
     * <h2>为什么必须有</h2>
     * <p>归档里的<b>每个文件</b>都要一次 {@code getFileDownloadUrl} —— 而该调用内部含
     * {@code objectExists}（OSS HEAD，<b>远程</b>）+ 预签名生成。分享一个大文件夹（数千文件）时
     * 就退化成数千次串行远程调用，请求必然超时，同时长时间占用工作线程。</p>
     *
     * <p>取 200 的理由：这是「IM 卡片」的归档场景（用户在会话里点开归档下载），
     * 不是全量备份通道；200 个文件已能覆盖绝大多数会话分享，且 200 次远程调用在可接受量级内
     * （配合已有的 30s 级预签名与会话超时）。超限时明确拒绝并提示改用完整分享下载，
     * 而不是静默截断（截断会让用户拿到"少文件"的压缩包且无从察觉）。</p>
     */
    private static final int MAX_ARCHIVE_ENTRIES = 200;

    /**
     * F9：归档递归深度上限，与 {@code FileOperationHelper.MAX_FOLDER_DEPTH} 取同一口径。
     * <p>修复前 {@link #collectFolderArchiveEntries} 是<b>无深度上限</b>的递归 ——
     * 数据若存在环（历史脏数据/并发写入造成的自引用），会栈溢出并拖垮请求线程。</p>
     */
    private static final int MAX_ARCHIVE_FOLDER_DEPTH = 100;

    private final FileDomainValidator fileDomainValidator;
    private final FileQueryPort fileQueryPort;
    private final AuthServicePort authServicePort;

    public ImFileCardService(FileDomainValidator fileDomainValidator, FileQueryPort fileQueryPort, AuthServicePort authServicePort) {
        this.fileDomainValidator = fileDomainValidator;
        this.fileQueryPort = fileQueryPort;
        this.authServicePort = authServicePort;
    }

    @Override
    public FileCardSnapshotVO createSnapshot(FileCardSnapshotRequest request) {
        List<Long> normalizedFileIds = fileDomainValidator.normalizeFileIds(request == null ? null : request.getFileIds());
        Long currentUserId = authServicePort.getCurrentUserId();
        Map<Long, FileInfoDTO> fileInfoMap = requireAccessibleActiveFiles(normalizedFileIds, currentUserId);
        List<FileInfoDTO> orderedFiles = normalizedFileIds.stream()
                .map(fileInfoMap::get)
                .filter(Objects::nonNull)
                .toList();

        Long parentId = resolveCommonParentId(orderedFiles);
        String shareType = resolveShareType(orderedFiles);
        List<FileCardEntryVO> entries = orderedFiles.stream()
                .map(FileCardEntryVO::fromFileInfo)
                .toList();
        return new FileCardSnapshotVO(
                shareType,
                authServicePort.getCurrentUserId(),
                parentId,
                entries.size(),
                entries
        );
    }

    @Override
    public FileCardResolveVO resolve(FileCardResolveRequest request) {
        return resolve(request, authServicePort.getCurrentUserId());
    }

    FileCardResolveVO resolve(FileCardResolveRequest request, Long currentUserId) {
        List<FileCardEntryRequest> requestedEntries = normalizeEntries(request);
        Map<Long, FileCardEntryRequest> requestEntryMap = requestedEntries.stream()
                .collect(Collectors.toMap(FileCardEntryRequest::getFileId, entry -> entry, (left, right) -> left, LinkedHashMap::new));
        List<Long> fileIds = requestedEntries.stream().map(FileCardEntryRequest::getFileId).toList();
        List<FileInfoDTO> currentFiles = fileQueryPort.getFileInfoByIds(fileIds, currentUserId);
        Map<Long, FileInfoDTO> currentFileMap = currentFiles.stream()
                .collect(Collectors.toMap(FileInfoDTO::getId, fileInfo -> fileInfo, (left, right) -> left));

        boolean hasDeleted = false;
        boolean hasMoved = false;
        List<FileInfoDTO> activeFiles = new ArrayList<>();
        List<FileCardEntryVO> resolvedEntries = new ArrayList<>();

        for (Long fileId : fileIds) {
            FileCardEntryRequest requestedEntry = requestEntryMap.get(fileId);
            FileInfoDTO currentFile = currentFileMap.get(fileId);
            if (currentFile == null) {
                hasDeleted = true;
                resolvedEntries.add(FileCardEntryVO.fromRequest(requestedEntry));
                continue;
            }
            resolvedEntries.add(FileCardEntryVO.fromFileInfo(currentFile));
            if (!currentFile.isActive()) {
                hasDeleted = true;
                continue;
            }
            activeFiles.add(currentFile);
            if (isMoved(requestedEntry, currentFile)) {
                hasMoved = true;
            }
        }

        String status = resolveStatus(hasDeleted, hasMoved);
        String shareType = normalizeShareType(request.getShareType(), requestedEntries);
        String title = buildTitle(shareType, activeFiles, requestedEntries);
        Long folderParentId = resolveFolderParentId(shareType, activeFiles);
        String folderPath = resolveFolderPath(shareType, activeFiles, currentUserId);
        String downloadUrl = resolveDownloadUrl(shareType, activeFiles, status, currentUserId);
        List<FileCardArchiveEntryVO> archiveEntries = resolveArchiveEntries(shareType, activeFiles, status, currentUserId);

        return new FileCardResolveVO(
                status,
                shareType,
                title,
                folderParentId,
                folderPath,
                downloadUrl,
                resolvedEntries,
                archiveEntries
        );
    }

    private Map<Long, FileInfoDTO> requireAccessibleActiveFiles(List<Long> fileIds, Long currentUserId) {
        Map<Long, FileInfoDTO> fileInfoMap = fileQueryPort.getFileInfoByIds(fileIds, currentUserId).stream()
                .collect(Collectors.toMap(FileInfoDTO::getId, fileInfo -> fileInfo));
        for (Long fileId : fileIds) {
            FileInfoDTO fileInfo = fileInfoMap.get(fileId);
            if (fileInfo == null || !fileInfo.isActive()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "所选文件中包含已删除或不存在的数据");
            }
        }
        return fileInfoMap;
    }

    private List<FileCardEntryRequest> normalizeEntries(FileCardResolveRequest request) {
        List<FileCardEntryRequest> entries = request == null ? null : request.getEntries();
        if (entries == null || entries.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "entries 不能为空");
        }
        if (entries.stream().anyMatch(entry -> entry == null || entry.getFileId() == null)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "entries 中存在非法 fileId");
        }
        return entries;
    }

    private Long resolveCommonParentId(List<FileInfoDTO> files) {
        Long parentId = null;
        for (FileInfoDTO file : files) {
            if (parentId == null) {
                parentId = file.getParentId();
                continue;
            }
            if (!Objects.equals(parentId, file.getParentId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "多文件分享必须来自同一文件夹");
            }
        }
        return parentId;
    }

    private String resolveShareType(List<FileInfoDTO> files) {
        if (files.size() == 1) {
            return files.get(0).isFolder() ? SHARE_TYPE_SINGLE_FOLDER : SHARE_TYPE_SINGLE_FILE;
        }
        return SHARE_TYPE_MULTI_FILE;
    }

    private String normalizeShareType(String requestedShareType, List<FileCardEntryRequest> entries) {
        if (requestedShareType != null && !requestedShareType.isBlank()) {
            return requestedShareType;
        }
        if (entries.size() == 1) {
            return Integer.valueOf(0).equals(entries.get(0).getFileType()) ? SHARE_TYPE_SINGLE_FOLDER : SHARE_TYPE_SINGLE_FILE;
        }
        return SHARE_TYPE_MULTI_FILE;
    }

    private boolean isMoved(FileCardEntryRequest requestedEntry, FileInfoDTO currentFile) {
        return !Objects.equals(requestedEntry.getParentId(), currentFile.getParentId())
                || !Objects.equals(requestedEntry.getStorePath(), currentFile.getStorePath())
                || !Objects.equals(requestedEntry.getOriginalName(), currentFile.getOriginalName());
    }

    private String resolveStatus(boolean hasDeleted, boolean hasMoved) {
        if (hasDeleted) {
            return STATUS_DELETED;
        }
        if (hasMoved) {
            return STATUS_MOVED;
        }
        return STATUS_AVAILABLE;
    }

    private String buildTitle(String shareType, List<FileInfoDTO> activeFiles, List<FileCardEntryRequest> requestedEntries) {
        if (SHARE_TYPE_MULTI_FILE.equals(shareType)) {
            return "共 " + requestedEntries.size() + " 项";
        }
        FileInfoDTO activeFile = activeFiles.isEmpty() ? null : activeFiles.get(0);
        if (activeFile != null) {
            return activeFile.getOriginalName();
        }
        return requestedEntries.get(0).getOriginalName();
    }

    @Nullable
    private Long resolveFolderParentId(String shareType, List<FileInfoDTO> activeFiles) {
        if (activeFiles.isEmpty()) {
            return null;
        }
        if (SHARE_TYPE_SINGLE_FOLDER.equals(shareType)) {
            return activeFiles.get(0).getId();
        }
        return activeFiles.get(0).getParentId();
    }

    @Nullable
    private String resolveFolderPath(String shareType, List<FileInfoDTO> activeFiles, Long currentUserId) {
        if (activeFiles.isEmpty()) {
            return null;
        }
        if (SHARE_TYPE_SINGLE_FOLDER.equals(shareType)) {
            return normalizeFolderPath(activeFiles.get(0).getStorePath());
        }
        Long commonParentId = tryResolveCommonParentId(activeFiles);
        if (commonParentId == null || commonParentId < 0) {
            return "";
        }
        FileInfoDTO parentFolder = fileQueryPort.getFileInfoById(commonParentId, currentUserId);
        return parentFolder == null ? "" : normalizeFolderPath(parentFolder.getStorePath());
    }

    @Nullable
    private Long tryResolveCommonParentId(List<FileInfoDTO> files) {
        Long parentId = null;
        for (FileInfoDTO file : files) {
            if (parentId == null) {
                parentId = file.getParentId();
                continue;
            }
            if (!Objects.equals(parentId, file.getParentId())) {
                return null;
            }
        }
        return parentId;
    }

    @Nullable
    private String resolveDownloadUrl(String shareType, List<FileInfoDTO> activeFiles, String status, Long currentUserId) {
        if (!STATUS_AVAILABLE.equals(status) && !STATUS_MOVED.equals(status)) {
            return null;
        }
        if (!SHARE_TYPE_SINGLE_FILE.equals(shareType) || activeFiles.isEmpty()) {
            return null;
        }
        FileDownloadUrlVO downloadUrlVO = fileQueryPort.getFileDownloadUrl(activeFiles.get(0).getId(), currentUserId);
        return downloadUrlVO == null ? null : downloadUrlVO.getDownloadUrl();
    }

    private List<FileCardArchiveEntryVO> resolveArchiveEntries(String shareType,
                                                              List<FileInfoDTO> activeFiles,
                                                              String status,
                                                              Long currentUserId) {
        if (!STATUS_AVAILABLE.equals(status) && !STATUS_MOVED.equals(status)) {
            return List.of();
        }
        if (SHARE_TYPE_SINGLE_FILE.equals(shareType) || activeFiles.isEmpty()) {
            return List.of();
        }
        List<FileInfoDTO> sortedFiles = activeFiles.stream()
                .sorted(Comparator.comparing(FileInfoDTO::getId))
                .toList();
        List<FileCardArchiveEntryVO> result = new ArrayList<>();
        for (FileInfoDTO file : sortedFiles) {
            if (file.isFolder()) {
                result.addAll(collectFolderArchiveEntries(file, "", currentUserId, result.size()));
            } else {
                result.add(buildArchiveEntry(file, "", currentUserId));
            }
        }
        return result;
    }

    /**
     * 递归收集文件夹下的归档条目。
     *
     * <p>F9：新增 {@code depth}（防环/防栈溢出）与 {@code baseCount}（条目上限的全局累计）。
     * 上限与深度都必须<b>跨整个递归</b>生效，而不是每层各算各的 —— 每层独立计数时
     * 「1000 个文件夹 × 200 个文件」依然是 20 万次远程调用。</p>
     *
     * @param depth     当前深度（顶层文件夹为 0）
     * @param baseCount 进入本次调用前已累计的条目数
     */
    private List<FileCardArchiveEntryVO> collectFolderArchiveEntries(FileInfoDTO folder,
                                                                    String basePath,
                                                                    Long currentUserId,
                                                                    int depth,
                                                                    int baseCount) {
        if (depth > MAX_ARCHIVE_FOLDER_DEPTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "文件夹层级超过上限 " + MAX_ARCHIVE_FOLDER_DEPTH + " 层，无法生成归档");
        }
        String currentPath = joinArchivePath(basePath, folder.getOriginalName());
        List<FileCardArchiveEntryVO> result = new ArrayList<>();
        List<FileInfoDTO> children = fileQueryPort.getChildrenByParentIdWithDeleted(folder.getId(), currentUserId).stream()
                .filter(FileInfoDTO::isActive)
                .sorted(Comparator.comparing(FileInfoDTO::getId))
                .toList();
        for (FileInfoDTO child : children) {
            if (result.size() + baseCount >= MAX_ARCHIVE_ENTRIES) {
                throw archiveTooLarge();
            }
            if (child.isFolder()) {
                result.addAll(collectFolderArchiveEntries(child, currentPath, currentUserId,
                        depth + 1, baseCount + result.size()));
            } else {
                result.add(buildArchiveEntry(child, currentPath, currentUserId));
            }
        }
        return result;
    }

    /** 条目超限的统一错误（F9）：明确告知上限与替代方式，避免用户拿到被静默截断的归档。 */
    private BusinessException archiveTooLarge() {
        return new BusinessException(ErrorCode.BAD_REQUEST,
                "归档文件数量超过上限 " + MAX_ARCHIVE_ENTRIES + "，请改用完整分享下载");
    }

    /** 兼容既有单参调用点（顶层文件夹 depth=0、尚未累计条目）。 */
    private List<FileCardArchiveEntryVO> collectFolderArchiveEntries(FileInfoDTO folder,
                                                                    String basePath,
                                                                    Long currentUserId,
                                                                    int baseCount) {
        return collectFolderArchiveEntries(folder, basePath, currentUserId, 0, baseCount);
    }

    private FileCardArchiveEntryVO buildArchiveEntry(FileInfoDTO file, String basePath, Long currentUserId) {
        FileDownloadUrlVO downloadUrlVO = fileQueryPort.getFileDownloadUrl(file.getId(), currentUserId);
        return new FileCardArchiveEntryVO(
                file.getOriginalName(),
                joinArchivePath(basePath, file.getOriginalName()),
                downloadUrlVO.getDownloadUrl()
        );
    }

    private String joinArchivePath(String basePath, String fileName) {
        String normalizedBase = basePath == null ? "" : basePath.trim().replace('\\', '/');
        String normalizedName = fileName == null ? "" : fileName.trim().replace('\\', '/');
        if (normalizedBase.isEmpty()) {
            return normalizedName;
        }
        return normalizedBase + "/" + normalizedName;
    }

    private String normalizeFolderPath(String storePath) {
        if (storePath == null || storePath.isBlank()) {
            return "";
        }
        return storePath.startsWith("/") ? storePath.substring(1) : storePath;
    }

}
