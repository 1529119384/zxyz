package uno.acloud.share.common;

import uno.acloud.share.vo.ShareStatusDataVO;

/**
 * 统一维护分享状态的展示元数据，避免控制层和服务层重复维护映射。
 */
public final class ShareStatusMeta {

    private ShareStatusMeta() {
    }

    public static ShareStatusDataVO toData(Integer status) {
        return new ShareStatusDataVO(status, textOf(status));
    }

    public static String textOf(Integer status) {
        return switch (status) {
            case ShareStatus.NORMAL -> "生效中";
            case ShareStatus.CANCELED -> "已取消";
            case ShareStatus.EXPIRED -> "已过期";
            case ShareStatus.ACCESS_LIMIT_REACHED -> "次数用尽";
            default -> "未知状态";
        };
    }
}
