package uno.acloud.share.service.impl;

import uno.acloud.share.infrastructure.client.model.ShareFileProjection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ShareFileResolveContext {
    private final Map<Long, List<ShareFileProjection>> sharedRootFileInfos = new LinkedHashMap<>();

    public List<ShareFileProjection> getSharedRootFileInfos(Long shareId) {
        return sharedRootFileInfos.get(shareId);
    }

    public void putSharedRootFileInfos(Long shareId, List<ShareFileProjection> fileInfos) {
        sharedRootFileInfos.put(shareId, fileInfos);
    }
}
