package uno.acloud.share.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.PageResult;
import uno.acloud.common.Result;
import uno.acloud.share.service.SharePort;
import uno.acloud.share.vo.ShareMyListItemVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShareController#getMyShares 的信封与透传测试（07-P1-4）。
 *
 * <p>信封从 {total, rows} 统一成 PageResult{page, pageSize, total, list} 之后，
 * <b>字段名本身就是契约</b>：前端按 list 取数，字段名写错就是整页空白，
 * 而且这种错在编译期看不出来。所以这里断言的是信封形状与参数透传。</p>
 */
@ExtendWith(MockitoExtension.class)
class ShareControllerTest {

    private static final Long USER_ID = 11L;

    @Mock
    private SharePort sharePort;

    private ShareController controller;

    @BeforeEach
    void setUp() {
        controller = new ShareController(sharePort);
    }

    @Test
    void getMyShares_passesPagingThroughAndWrapsInPageResultEnvelope() {
        PageResult<ShareMyListItemVO> page = PageResult.of(2, 10, 41L, List.of());
        when(sharePort.getMyShares(USER_ID, 2, 10)).thenReturn(page);

        Result<PageResult<ShareMyListItemVO>> result = controller.getMyShares(USER_ID, 2, 10);

        verify(sharePort).getMyShares(USER_ID, 2, 10);
        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertEquals(2, result.getData().getPage());
        assertEquals(10, result.getData().getPageSize());
        assertEquals(41L, result.getData().getTotal());
        assertEquals(List.of(), result.getData().getList());
    }

    @Test
    void getMyShares_doesNotReshapePageSizeItself() {
        // 归一化与上限钳制只在 ShareManager 里做一次。Controller 若自作主张再钳一次，
        // 会掩盖 Service 的默认值差异（10 vs PageResult.DEFAULT_PAGE_SIZE=20），
        // 且不会有任何测试变红 —— 所以这条专门钉住「Controller 只透传」。
        PageResult<ShareMyListItemVO> page = PageResult.of(1, 9999, 0L, List.of());
        when(sharePort.getMyShares(USER_ID, 1, 9999)).thenReturn(page);

        Result<PageResult<ShareMyListItemVO>> result = controller.getMyShares(USER_ID, 1, 9999);

        verify(sharePort).getMyShares(USER_ID, 1, 9999);
        assertEquals(9999, result.getData().getPageSize());
    }
}
