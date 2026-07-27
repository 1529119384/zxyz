package uno.acloud.share.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import uno.acloud.share.infrastructure.client.ShareFileServiceClient;
import uno.acloud.share.infrastructure.client.model.ShareFileProjection;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.entity.ShareItem;
import uno.acloud.share.infrastructure.mapper.ShareMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class ShareFileResolver {

    private final ShareMapper shareMapper;
    private final ShareFileServiceClient fileServiceClient;

    public ShareFileResolver(ShareMapper shareMapper, ShareFileServiceClient fileServiceClient) {
        this.shareMapper = shareMapper;
        this.fileServiceClient = fileServiceClient;
    }

    public List<ShareFileProjection> getSharedRootFileInfos(Long shareId) {
        return getSharedRootFileInfos(shareId, null);
    }

    public List<ShareFileProjection> getSharedRootFileInfos(Long shareId, ShareFileResolveContext context) {
        List<ShareFileProjection> cachedFileInfos = context == null ? null : context.getSharedRootFileInfos(shareId);
        if (cachedFileInfos != null) {
            return cachedFileInfos;
        }

        List<Long> fileIds = shareMapper.listItemsByShareId(shareId).stream()
                .map(ShareItem::getFileId)
                .collect(Collectors.toList());
        if (fileIds.isEmpty()) {
            return cacheSharedRootFileInfos(context, shareId, List.of());
        }
        Map<Long, ShareFileProjection> fileInfoMap = fileServiceClient.getShareFileProjections(fileIds).stream()
                .collect(Collectors.toMap(ShareFileProjection::getId, fileInfo -> fileInfo, (left, right) -> left, LinkedHashMap::new));
        List<ShareFileProjection> result = new ArrayList<>();
        for (Long fileId : fileIds) {
            ShareFileProjection fileInfo = fileInfoMap.get(fileId);
            if (fileInfo != null && fileInfo.isActive()) {
                result.add(fileInfo);
            }
        }
        return cacheSharedRootFileInfos(context, shareId, List.copyOf(result));
    }

    public boolean isFileInShareScope(Long shareId, ShareFileProjection candidate) {
        return isFileInShareScope(shareId, candidate, null);
    }

    public boolean isFileInShareScope(Long shareId, ShareFileProjection candidate, ShareFileResolveContext context) {
        for (ShareFileProjection sharedRoot : getSharedRootFileInfos(shareId, context)) {
            if (Objects.equals(sharedRoot.getId(), candidate.getId())) {
                return true;
            }
            if (sharedRoot.isFolder()
                    && StringUtils.isNotBlank(sharedRoot.getStorePath())
                    && StringUtils.isNotBlank(candidate.getStorePath())
                    && candidate.getStorePath().startsWith(sharedRoot.getStorePath() + "/")) {
                return true;
            }
        }
        return false;
    }

    private List<ShareFileProjection> cacheSharedRootFileInfos(ShareFileResolveContext context, Long shareId, List<ShareFileProjection> fileInfos) {
        if (context != null) {
            context.putSharedRootFileInfos(shareId, fileInfos);
        }
        return fileInfos;
    }
}
