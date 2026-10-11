package uno.acloud.file.service;

import uno.acloud.file.dto.im.FileCardResolveRequest;
import uno.acloud.file.dto.im.FileCardSnapshotRequest;
import uno.acloud.file.vo.im.FileCardResolveVO;
import uno.acloud.file.vo.im.FileCardSnapshotVO;

/**
 * IM 文件卡片端口。
 *
 * <p>P1-1（ISSUE/54 §2.7）：操作者 userId 改为<b>方法参数</b>显式传递。
 * 内部链路（/api/internal/im-file-cards/*）不存在用户会话，此前端点内调用
 * {@code authServicePort.getCurrentUserId()}（Sa-Token 语义）必然抛
 * NotLoginException —— IM 文件卡片主路径自 6 月起 401 断裂。
 * 现由 Controller 从 {@code X-Acting-User-Id} 请求头解析后传入，
 * 本端口不再依赖 Sa-Token 会话语义。</p>
 */
public interface ImFileCardPort {

    FileCardSnapshotVO createSnapshot(FileCardSnapshotRequest request, Long currentUserId);

    FileCardResolveVO resolve(FileCardResolveRequest request, Long currentUserId);
}
