package uno.acloud.share.service.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import uno.acloud.share.vo.ShareVerifyResponseVO;

import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@AllArgsConstructor
public class ShareVerifyResult {
    private ShareVerifyResponseVO response;
    private String accessToken;
    private LocalDateTime expireTime;

    public static ShareVerifyResult passedWithoutNewToken() {
        return new ShareVerifyResult(new ShareVerifyResponseVO(true), null, null);
    }

    public static ShareVerifyResult passedWithToken(String accessToken, LocalDateTime expireTime) {
        return new ShareVerifyResult(new ShareVerifyResponseVO(true), accessToken, expireTime);
    }
}
