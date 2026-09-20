package uno.acloud.share.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.RequestParam;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.PageResult;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.config.ShareProperties;
import uno.acloud.share.config.ShareTimeSource;
import uno.acloud.share.controller.ShareController;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.mapper.ShareMapper;
import uno.acloud.share.vo.ShareMyListItemVO;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShareManager#getMyShares 的分页语义测试（07-P1-4）。
 *
 * <p><b>这些用例的重点不是「能翻页」</b>，而是「归一化后的 offset / pageSize 真的下发给
 * 查询」以及「回给前端的 page / pageSize 与真正下发的查询一致」——
 * 只断言返回值会漏掉「钳制算完了却没生效」这类改动（同一口径见 ConfigAdminControllerTest）。</p>
 *
 * <p>另一条防线是 {@link #sharePageSizeDefaultIsConsistentBetweenControllerAndManager()}：
 * Controller 的 {@code @RequestParam(defaultValue)} 与 ShareManager 的默认常量分处两个文件，
 * 只在一边改就会让「不带参数请求」和「带参数请求」得到不同页长，且不会有任何测试变红 ——
 * 这里把它变成会失败的断言。</p>
 */
@ExtendWith(MockitoExtension.class)
class ShareManagerTest {

    private static final Long USER_ID = 7L;

    @Mock
    private ShareMapper shareMapper;

    @Mock
    private ShareValidator shareValidator;

    @Mock
    private ShareInputNormalizer shareInputNormalizer;

    @Mock
    private ShareViewMapper shareViewMapper;

    @Mock
    private ShareStatusCalculator shareStatusCalculator;

    @Mock
    private ShareProperties shareProperties;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TransactionHelper transactionHelper;

    @Mock
    private ShareTimeSource timeSource;

    private ShareManager shareManager;

    @BeforeEach
    void setUp() {
        shareManager = new ShareManager(shareMapper, shareValidator, shareInputNormalizer,
                shareViewMapper, shareStatusCalculator, shareProperties, passwordEncoder,
                transactionHelper, timeSource);
    }

    private void stubTotal(int total) {
        when(shareMapper.countByUserId(USER_ID)).thenReturn(total);
    }

    private void stubPageRows(Share... rows) {
        when(shareMapper.listPageByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenReturn(List.of(rows));
    }

    /** 捕获真正下发给 SQL 的 offset / pageSize。 */
    private int[] captureOffsetAndSize() {
        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(shareMapper).listPageByUserId(eq(USER_ID), offset.capture(), size.capture());
        return new int[] {offset.getValue(), size.getValue()};
    }

    // ---------- 默认值：必须是 10，不是 PageResult.DEFAULT_PAGE_SIZE(20) ----------

    @Test
    void getMyShares_nullPagingFallsBackToLegacyDefaultSizeTen() {
        stubTotal(35);
        stubPageRows();

        PageResult<ShareMyListItemVO> result = shareManager.getMyShares(USER_ID, null, null);

        int[] sent = captureOffsetAndSize();
        assertEquals(0, sent[0]);
        assertEquals(10, sent[1]);

        assertEquals(1, result.getPage());
        assertEquals(10, result.getPageSize());
        assertEquals(35L, result.getTotal());
    }

    @Test
    void getMyShares_nonPositivePageAndSizeFallBackToDefaults() {
        stubTotal(35);
        stubPageRows();

        PageResult<ShareMyListItemVO> result = shareManager.getMyShares(USER_ID, 0, -5);

        int[] sent = captureOffsetAndSize();
        assertEquals(0, sent[0]);
        assertEquals(10, sent[1]);
        assertEquals(1, result.getPage());
    }

    // ---------- 上限闸门：旧实现缺的就是这一条 ----------

    @Test
    void getMyShares_clampsOversizedPageSizeToUpperBound() {
        stubTotal(100);
        stubPageRows();

        PageResult<ShareMyListItemVO> result = shareManager.getMyShares(USER_ID, 1, 9999);

        int[] sent = captureOffsetAndSize();
        assertEquals(0, sent[0]);
        assertEquals(PageResult.MAX_PAGE_SIZE, sent[1]);
        // 回给前端的 pageSize 必须与真正下发的查询一致，否则前端按它算总页数会算错。
        assertEquals(PageResult.MAX_PAGE_SIZE, result.getPageSize());
    }

    // ---------- 正常路径不得被改动 ----------

    @Test
    void getMyShares_keepsValidPagingUntouched() {
        stubTotal(100);
        stubPageRows();

        PageResult<ShareMyListItemVO> result = shareManager.getMyShares(USER_ID, 3, 25);

        int[] sent = captureOffsetAndSize();
        assertEquals(50, sent[0]);
        assertEquals(25, sent[1]);
        assertEquals(3, result.getPage());
        assertEquals(25, result.getPageSize());
        assertEquals(100L, result.getTotal());
    }

    @Test
    void getMyShares_offsetBeyondTotalReturnsEmptyPageWithoutQuerying() {
        stubTotal(5);

        PageResult<ShareMyListItemVO> result = shareManager.getMyShares(USER_ID, 3, 10);

        // 第 3 页（offset 20）已越过总数 5 ⇒ 不该再打一次必然为空的查询。
        verify(shareMapper, never()).listPageByUserId(anyLong(), anyInt(), anyInt());
        assertEquals(List.of(), result.getList());
        assertEquals(5L, result.getTotal());
        assertEquals(3, result.getPage());
    }

    @Test
    void getMyShares_noSharesAtAllStillCarriesPagingEnvelope() {
        stubTotal(0);

        PageResult<ShareMyListItemVO> result = shareManager.getMyShares(USER_ID, 2, 10);

        verify(shareMapper, never()).listPageByUserId(anyLong(), anyInt(), anyInt());
        // list 为 null 会迫使前端到处防 null，这里统一落成空列表。
        assertEquals(List.of(), result.getList());
        assertEquals(0L, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals(10, result.getPageSize());
    }

    @Test
    void getMyShares_mapsEveryRowThroughViewMapper() {
        stubTotal(2);
        stubPageRows(new Share(), new Share());

        PageResult<ShareMyListItemVO> result = shareManager.getMyShares(USER_ID, 1, 10);

        // rows 的条数即当前页记录数；映射细节由 ShareViewMapper 自理。
        assertEquals(2, result.getList().size());
        verify(shareStatusCalculator).batchRefreshStatusIfNeeded(any());
    }

    // ---------- 参数校验先于任何查询 ----------

    @Test
    void getMyShares_invalidUserIdFailsBeforeQuerying() {
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "用户 id 不能为空"))
                .when(shareValidator).validateUserId(null);

        assertThrows(BusinessException.class, () -> shareManager.getMyShares(null, 1, 10));
        verify(shareMapper, never()).countByUserId(anyLong());
    }

    // ---------- 跨文件一致性：HTTP 默认值 vs Service 默认值 ----------

    @Test
    void sharePageSizeDefaultIsConsistentBetweenControllerAndManager() throws Exception {
        RequestParam sizeParam = ShareController.class
                .getMethod("getMyShares", Long.class, Integer.class, Integer.class)
                .getParameters()[2]
                .getAnnotation(RequestParam.class);
        Field field = ShareManager.class.getDeclaredField("DEFAULT_PAGE_SIZE");
        field.setAccessible(true);
        int managerDefault = field.getInt(null);

        assertEquals(10, managerDefault, "ShareManager 的默认页长发生漂移");
        assertEquals(String.valueOf(managerDefault), sizeParam.defaultValue(),
                "Controller 的 defaultValue 与 ShareManager 的默认常量不一致");
    }
}
