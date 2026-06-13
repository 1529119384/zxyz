package uno.acloud.share.service.impl;

import uno.acloud.dto.FileInfoDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ShareFileResolveContext {
    private final Map<Long, List<FileInfoDTO>> sharedRootFileInfos = new LinkedHashMap<>();

    public List<FileInfoDTO> getSharedRootFileInfos(Long shareId) {
        return sharedRootFileInfos.get(shareId);
    }

    public void putSharedRootFileInfos(Long shareId, List<FileInfoDTO> fileInfos) {
        sharedRootFileInfos.put(shareId, fileInfos);
    }
}
