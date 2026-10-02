package uno.acloud.im.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.dto.InternalBatchSystemNotificationRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link InternalSystemNotificationService#batchNotify} 测试（B-22）。
 *
 * <p>核心断言：广播必须走<b>批量插入</b>路径。这条端点由 team-service 按 500/批调用，
 * 逐用户循环会产生 500 次 insert；批量版本收敛为 1 条 batchInsert，
 * 且去重/过滤逻辑必须与旧实现保持一致（否则广播范围会悄悄变化）。</p>
 */
@ExtendWith(MockitoExtension.class)
class InternalSystemNotificationServiceTest {

    @Mock
    private SystemNotificationService systemNotificationService;

    private InternalSystemNotificationService service;

    @BeforeEach
    void setUp() {
        service = new InternalSystemNotificationService(systemNotificationService);
    }

    private InternalBatchSystemNotificationRequest request(List<Long> userIds) {
        InternalBatchSystemNotificationRequest request = new InternalBatchSystemNotificationRequest();
        request.setUserIds(userIds);
        request.setType("TEAM_ANNOUNCEMENT");
        request.setTitle("公告");
        request.setContent("内容");
        request.setBusinessId(88L);
        request.setTeamId(5L);
        return request;
    }

    @Test
    void batchNotify_usesBatchInsertInsteadOfPerUserLoop() {
        service.batchNotify(request(List.of(1L, 2L, 3L)));

        // 一次批量调用，而不是三次单条调用
        verify(systemNotificationService).batchCreateNotifications(
                eq(List.of(1L, 2L, 3L)), eq("TEAM_ANNOUNCEMENT"), eq("公告"), eq("内容"),
                eq("TEAM_ANNOUNCEMENT"), eq(88L), eq(5L));
        verify(systemNotificationService, never()).createNotification(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void batchNotify_dedupesAndFiltersInvalidUserIds() {
        // 重复用户只应通知一次；null / <=0 应被丢弃
        service.batchNotify(request(java.util.Arrays.asList(1L, 1L, null, 0L, -5L, 2L)));

        verify(systemNotificationService).batchCreateNotifications(
                eq(List.of(1L, 2L)), any(), any(), any(), any(), any(), any());
    }

    @Test
    void batchNotify_usesExplicitBusinessTypeWhenProvided() {
        InternalBatchSystemNotificationRequest request = request(List.of(1L));
        request.setBusinessType(" CUSTOM ");

        service.batchNotify(request);

        verify(systemNotificationService).batchCreateNotifications(
                eq(List.of(1L)), any(), any(), any(), eq("CUSTOM"), any(), any());
    }

    @Test
    void batchNotify_emptyOrAllInvalidUserIds_isNoOp() {
        service.batchNotify(request(List.of()));
        service.batchNotify(request(java.util.Arrays.asList(null, 0L)));

        verifyNoInteractions(systemNotificationService);
    }

    @Test
    void batchNotify_nullRequest_throwsBadRequest() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.batchNotify(null));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verifyNoInteractions(systemNotificationService);
    }

    @Test
    void batchNotify_blankRequiredFields_throwBeforeTouchingService() {
        InternalBatchSystemNotificationRequest blankTitle = request(List.of(1L));
        blankTitle.setTitle("   ");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.batchNotify(blankTitle));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verifyNoInteractions(systemNotificationService);
    }
}
