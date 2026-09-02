package uno.acloud.share.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.share.infrastructure.mapper.ShareMapper;

/**
 * 用户资料（用户名）同步服务。
 * <p>用户资料更新时，同步分享表中冗余的用户名，保证分享展示一致性。</p>
 */
@Slf4j
@Service
public class ShareUserProfileSyncService {

    private final ShareMapper shareMapper;

    public ShareUserProfileSyncService(ShareMapper shareMapper) {
        this.shareMapper = shareMapper;
    }

    /**
     * 按 userId 更新其所有分享记录中冗余的用户名。
     * 幂等：仅做一次 UPDATE，无记录时影响 0 行，可安全重试。
     *
     * @param userId   用户 ID
     * @param username 最新用户名
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncUsername(long userId, String username) {
        if (userId <= 0) {
            return;
        }
        if (username == null) {
            username = "";
        }
        int updated = shareMapper.updateUsernameByUserId(userId, username);
        log.info("同步用户分享用户名: userId={}, username={}, updated={}", userId, username, updated);
    }
}
