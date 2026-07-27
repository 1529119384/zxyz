package uno.acloud.share.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.ShareErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.infrastructure.client.ShareFileServiceClient;
import uno.acloud.share.infrastructure.client.model.ShareFileProjection;
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

    public ShareDownloadResponseVO getShareStreamUrl(String shareKey, Long fileId, String shareAccessToken) {
        if (fileId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileId 不能为空");
        }
        Share share = shareAccessService.requireAccessibleShare(shareKey, shareAccessToken);
        requireDownloadableSharedFile(share.getId(), fileId, new ShareFileResolveContext());

        // 获取文件信息
        ShareFileProjection fileInfo = fileServiceClient.getShareProjection(fileId);
        if (fileInfo == null || !fileInfo.isActive()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }

        // 从 file-service 获取存储流信息（内部接口）
        String streamInfoUrl = fileServiceClient.getFileStreamInfo(fileId);
        return new ShareDownloadResponseVO(streamInfoUrl);
    }

    private List<ShareFilesResponseItemVO> listShareFiles(Share share, String normalizedPath, ShareFileResolveContext resolveContext) {
        List<ShareFileProjection> sharedRoots = shareFileResolver.getSharedRootFileInfos(share.getId(), resolveContext);
        List<ShareFileProjection> targetFiles;
        if (StringUtils.isBlank(normalizedPath)) {
            targetFiles = sharedRoots;
        } else {
            ShareFileProjection currentFolder = resolveSharedFolderByPath(sharedRoots, normalizedPath);
            targetFiles = fileServiceClient.getShareChildren(currentFolder.getId());
        }
        return targetFiles.stream()
                .map(fileInfo -> shareViewMapper.toShareFilesResponseItemVO(fileInfo, shareValidator.isActive(fileInfo)))
                .sorted(shareViewMapper.shareFileComparator())
                .collect(Collectors.toList());
    }

    private ShareFileProjection resolveSharedFolderByPath(List<ShareFileProjection> sharedRoots, String normalizedPath) {
        List<String> segments = shareInputNormalizer.splitPath(normalizedPath);
        if (segments.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "path 非法");
        }
        ShareFileProjection currentFolder = sharedRoots.stream()
                .filter(ShareFileProjection::isFolder)
                .filter(fileInfo -> Objects.equals(fileInfo.getOriginalName(), segments.get(0)))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "path 非法"));
        if (!shareValidator.isActive(currentFolder)) {
            throw new BusinessException(ShareErrorCode.SHARE_STATUS_INVALID.getCode(), "分享目录已失效");
        }

        // 注意：此处为顺序依赖——每次迭代的 parentId 取决于上一次查询结果，无法预收集 ID 做批量查询。
        // getShareChildrenByParentIds 批量接口适用于已知全部 parentId 的场景（如 FileQueryService 中的并行查询）。
        // 如需消除此处的 N+1，需在 file-service 新增按完整路径段查询的接口（如 POST /api/internal/files/resolve-share-path）。
        for (int i = 1; i < segments.size(); i++) {
            String segment = segments.get(i);
            List<ShareFileProjection> children = fileServiceClient.getShareChildren(currentFolder.getId());
            if (children == null || children.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "path 非法");
            }
            ShareFileProjection nextFolder = children.stream()
                    .filter(ShareFileProjection::isFolder)
                    .filter(fileInfo -> Objects.equals(fileInfo.getOriginalName(), segment))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "path 非法"));
            if (!shareValidator.isActive(nextFolder)) {
                throw new BusinessException(ShareErrorCode.SHARE_STATUS_INVALID.getCode(), "分享目录已失效");
            }
            currentFolder = nextFolder;
        }
        return currentFolder;
    }

    private ShareFileProjection requireDownloadableSharedFile(Long shareId, Long fileId, ShareFileResolveContext resolveContext) {
        ShareFileProjection fileInfo = fileServiceClient.getShareProjection(fileId);
        if (fileInfo == null || !fileInfo.isFile()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        if (!shareValidator.isActive(fileInfo)) {
            throw new BusinessException(ShareErrorCode.SHARE_STATUS_INVALID.getCode(), "文件已失效，无法下载");
        }
        if (StringUtils.isBlank(fileInfo.getUuidName())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "下载对象不存在");
        }
        if (!shareFileResolver.isFileInShareScope(shareId, fileInfo, resolveContext)) {
            throw new BusinessException(ShareErrorCode.SHARE_FILE_OUT_OF_SCOPE.getCode(), "文件不在分享范围内");
        }
        return fileInfo;
    }
}
