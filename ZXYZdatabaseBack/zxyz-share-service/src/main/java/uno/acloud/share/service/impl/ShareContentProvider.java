package uno.acloud.share.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.infrastructure.client.ShareFileServiceClient;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.vo.ShareDownloadResponseVO;
import uno.acloud.share.vo.ShareFilesResponseItemVO;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class ShareContentProvider {

    private final ShareFileServiceClient fileServiceClient;
    private final ShareValidator shareValidator;
    private final ShareInputNormalizer shareInputNormalizer;
    private final ShareFileResolver shareFileResolver;
    private final ShareViewMapper shareViewMapper;
    private final ShareAccessManager shareAccessService;

    public ShareContentProvider(ShareFileServiceClient fileServiceClient,
                               ShareValidator shareValidator,
                               ShareInputNormalizer shareInputNormalizer,
                               ShareFileResolver shareFileResolver,
                               ShareViewMapper shareViewMapper,
                               ShareAccessManager shareAccessService) {
        this.fileServiceClient = fileServiceClient;
        this.shareValidator = shareValidator;
        this.shareInputNormalizer = shareInputNormalizer;
        this.shareFileResolver = shareFileResolver;
        this.shareViewMapper = shareViewMapper;
        this.shareAccessService = shareAccessService;
    }

    public List<ShareFilesResponseItemVO> getShareFiles(String shareKey, String path, String shareAccessToken) {
        Share share = shareAccessService.requireAccessibleShare(shareKey, shareAccessToken);
        return listShareFiles(share, shareInputNormalizer.normalizePath(path), new ShareFileResolveContext());
    }

    public ShareDownloadResponseVO getShareDownloadUrl(String shareKey, Long fileId, String shareAccessToken) {
        if (fileId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileId 不能为空");
        }
        Share share = shareAccessService.requireAccessibleShare(shareKey, shareAccessToken);
        requireDownloadableSharedFile(share.getId(), fileId, new ShareFileResolveContext());
        return new ShareDownloadResponseVO(fileServiceClient.getShareDownloadUrl(fileId));
    }

    private List<ShareFilesResponseItemVO> listShareFiles(Share share, String normalizedPath, ShareFileResolveContext resolveContext) {
        List<FileInfoDTO> sharedRoots = shareFileResolver.getSharedRootFileInfos(share.getId(), resolveContext);
        List<FileInfoDTO> targetFiles;
        if (StringUtils.isBlank(normalizedPath)) {
            targetFiles = sharedRoots;
        } else {
            FileInfoDTO currentFolder = resolveSharedFolderByPath(sharedRoots, normalizedPath);
            targetFiles = fileServiceClient.getShareChildren(currentFolder.getId());
        }
        return targetFiles.stream()
                .map(fileInfo -> shareViewMapper.toShareFilesResponseItemVO(fileInfo, shareValidator.isActive(fileInfo)))
                .sorted(shareViewMapper.shareFileComparator())
                .collect(Collectors.toList());
    }

    private FileInfoDTO resolveSharedFolderByPath(List<FileInfoDTO> sharedRoots, String normalizedPath) {
        List<String> segments = shareInputNormalizer.splitPath(normalizedPath);
        if (segments.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "path 非法");
        }
        FileInfoDTO currentFolder = sharedRoots.stream()
                .filter(FileInfoDTO::isFolder)
                .filter(fileInfo -> Objects.equals(fileInfo.getOriginalName(), segments.get(0)))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "path 非法"));
        if (!shareValidator.isActive(currentFolder)) {
            throw new BusinessException(ErrorCode.SHARE_STATUS_INVALID, "分享目录已失效");
        }
        for (int i = 1; i < segments.size(); i++) {
            String segment = segments.get(i);
            FileInfoDTO nextFolder = fileServiceClient.getShareChildren(currentFolder.getId()).stream()
                    .filter(fileInfo -> Objects.equals(fileInfo.getOriginalName(), segment))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "path 非法"));
            if (!nextFolder.isFolder()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "path 非法");
            }
            if (!shareValidator.isActive(nextFolder)) {
                throw new BusinessException(ErrorCode.SHARE_STATUS_INVALID, "分享目录已失效");
            }
            currentFolder = nextFolder;
        }
        return currentFolder;
    }

    private FileInfoDTO requireDownloadableSharedFile(Long shareId, Long fileId, ShareFileResolveContext resolveContext) {
        FileInfoDTO fileInfo = fileServiceClient.getFileInfoById(fileId);
        if (fileInfo == null || !fileInfo.isFile()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        if (!shareValidator.isActive(fileInfo)) {
            throw new BusinessException(ErrorCode.SHARE_STATUS_INVALID, "文件已失效，无法下载");
        }
        if (StringUtils.isBlank(fileInfo.getUuidName())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "下载对象不存在");
        }
        if (!shareFileResolver.isFileInShareScope(shareId, fileInfo, resolveContext)) {
            throw new BusinessException(ErrorCode.SHARE_FILE_OUT_OF_SCOPE, "文件不在分享范围内");
        }
        return fileInfo;
    }
}
