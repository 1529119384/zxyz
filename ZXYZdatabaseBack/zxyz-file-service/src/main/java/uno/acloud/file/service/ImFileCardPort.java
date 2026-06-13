package uno.acloud.file.service;

import uno.acloud.file.dto.im.FileCardResolveRequest;
import uno.acloud.file.dto.im.FileCardSnapshotRequest;
import uno.acloud.file.vo.im.FileCardResolveVO;
import uno.acloud.file.vo.im.FileCardSnapshotVO;

public interface ImFileCardPort {

    FileCardSnapshotVO createSnapshot(FileCardSnapshotRequest request);

    FileCardResolveVO resolve(FileCardResolveRequest request);
}
