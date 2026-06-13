package uno.acloud.share.service.impl;

import org.springframework.stereotype.Component;
import uno.acloud.share.common.ShareStatusMeta;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.vo.ShareFilesResponseItemVO;
import uno.acloud.share.vo.ShareMyListItemVO;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

@Component
public class ShareViewMapper {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ShareFilesResponseItemVO toShareFilesResponseItemVO(FileInfoDTO fileInfo, boolean active) {
        boolean invalid = !active;
        return new ShareFilesResponseItemVO(
                fileInfo.getId(),
                fileInfo.getOriginalName(),
                fileInfo.getFileType(),
                fileInfo.isFolder(),
                fileInfo.getCategory(),
                fileInfo.getDeleted(),
                invalid,
                invalid ? "已失效" : null,
                fileInfo.getFileSize(),
                fileInfo.getModifyTime()
        );
    }

    public Comparator<ShareFilesResponseItemVO> shareFileComparator() {
        return Comparator.comparingInt((ShareFilesResponseItemVO file) -> Boolean.TRUE.equals(file.getIsFolder()) ? 0 : 1)
                .thenComparing(ShareFilesResponseItemVO::getFileName, Comparator.nullsFirst(String::compareTo));
    }

    public ShareMyListItemVO toShareMyListItemVO(Share share, String shareUrl) {
        return new ShareMyListItemVO(
                share.getId(),
                share.getShareKey(),
                shareUrl,
                share.getPassword() != null && !share.getPassword().isEmpty(),
                resolveExpireType(share),
                share.getExpireTime(),
                share.getMaxAccessCount() == null ? 0 : share.getMaxAccessCount(),
                defaultZero(share.getCurrentAccessCount()),
                share.getStatus(),
                ShareStatusMeta.textOf(share.getStatus()),
                share.getCreateTime()
        );
    }

    public String resolveExpireType(Share share) {
        if (share.getExpireTime() == null) {
            return "forever";
        }
        long days = Duration.between(share.getCreateTime(), share.getExpireTime()).toDays();
        if (days <= 1) {
            return "1d";
        }
        if (days <= 7) {
            return "7d";
        }
        if (days <= 30) {
            return "30d";
        }
        return share.getExpireTime().format(TIME_FORMATTER);
    }

    private int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }
}
