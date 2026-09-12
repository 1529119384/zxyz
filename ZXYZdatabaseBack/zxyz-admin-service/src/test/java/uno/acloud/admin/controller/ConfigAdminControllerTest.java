package uno.acloud.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.admin.domain.SysConfig;
import uno.acloud.admin.domain.SysConfigAudit;
import uno.acloud.admin.mapper.SysConfigAuditMapper;
import uno.acloud.admin.mapper.SysConfigMapper;
import uno.acloud.admin.service.ConfigService;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.PageResult;
import uno.acloud.common.Result;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigAdminControllerTest {

    @Mock
    private ConfigService configService;

    @Mock
    private SysConfigMapper configMapper;

    @Mock
    private SysConfigAuditMapper auditMapper;

    private ConfigAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new ConfigAdminController(configService, configMapper, auditMapper);
    }

    // ---- listAll ----

    @Test
    void listAll_delegatesToMapper_returnsSuccess() {
        when(configMapper.selectList(any())).thenReturn(Collections.emptyList());

        Result<List<SysConfig>> result = controller.listAll();

        verify(configMapper).selectList(any());
        assertEquals(ErrorCode.SUCCESS, result.getCode());
    }

    // ---- getByKey ----

    @Test
    void getByKey_existingKey_returnsValue() {
        when(configService.get("app.name")).thenReturn("my-app");

        Result<String> result = controller.getByKey("app.name");

        verify(configService).get("app.name");
        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertEquals("my-app", result.getData());
    }

    @Test
    void getByKey_missingKey_returnsNotFound() {
        when(configService.get("nonexistent")).thenReturn(null);

        Result<String> result = controller.getByKey("nonexistent");

        verify(configService).get("nonexistent");
        assertEquals(ErrorCode.NOT_FOUND, result.getCode());
    }

    // ---- update ----

    @Test
    void update_validRequest_callsService() {
        ConfigAdminController.UpdateConfigRequest request = new ConfigAdminController.UpdateConfigRequest();
        request.setValue("new-value");

        Result<Void> result = controller.update("app.name", request, 1L);

        verify(configService).update("app.name", "new-value", 1L);
        assertEquals(ErrorCode.SUCCESS, result.getCode());
    }

    // ---- listAuditLogs（分页，07-P0-2）----

    /**
     * 审计日志此前是 selectList 全表返回。这些用例的重点不是"能翻页"，
     * 而是**归一化后的 page/pageSize 真的传到了查询里** ——
     * 只断言返回值会漏掉"钳制算完了却没生效"这类改动。
     */
    @SuppressWarnings("unchecked")
    private void stubAuditPage(long total, List<SysConfigAudit> records) {
        Page<SysConfigAudit> page = new Page<>(1, 20);
        page.setTotal(total);
        page.setRecords(records);
        doReturn(page).when(auditMapper).selectPage(any(Page.class), any());
    }

    @SuppressWarnings("unchecked")
    private Page<SysConfigAudit> captureQueryPage() {
        ArgumentCaptor<Page<SysConfigAudit>> captor = ArgumentCaptor.forClass(Page.class);
        verify(auditMapper).selectPage(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void listAuditLogs_defaultPaging_usesFirstPageAndDefaultSize() {
        stubAuditPage(35L, List.of(new SysConfigAudit(), new SysConfigAudit()));

        Result<PageResult<SysConfigAudit>> result = controller.listAuditLogs(null, null);

        Page<SysConfigAudit> query = captureQueryPage();
        assertEquals(1L, query.getCurrent());
        assertEquals(PageResult.DEFAULT_PAGE_SIZE, query.getSize());

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertEquals(1, result.getData().getPage());
        assertEquals(PageResult.DEFAULT_PAGE_SIZE, result.getData().getPageSize());
        assertEquals(35L, result.getData().getTotal());
        assertEquals(2, result.getData().getList().size());
    }

    @Test
    void listAuditLogs_clampsOversizedPageSizeToUpperBound() {
        stubAuditPage(0L, List.of());

        Result<PageResult<SysConfigAudit>> result = controller.listAuditLogs(1, 9999);

        Page<SysConfigAudit> query = captureQueryPage();
        assertEquals(1L, query.getCurrent());
        assertEquals(PageResult.MAX_PAGE_SIZE, query.getSize());
        // 回给前端的 pageSize 必须与真正下发的查询一致，否则前端按它算总页数会算错。
        assertEquals(PageResult.MAX_PAGE_SIZE, result.getData().getPageSize());
    }

    @Test
    void listAuditLogs_nonPositivePageAndSizeFallBackToDefaults() {
        stubAuditPage(0L, List.of());

        Result<PageResult<SysConfigAudit>> result = controller.listAuditLogs(0, -5);

        Page<SysConfigAudit> query = captureQueryPage();
        assertEquals(1L, query.getCurrent());
        assertEquals(PageResult.DEFAULT_PAGE_SIZE, query.getSize());
        assertEquals(1, result.getData().getPage());
    }

    @Test
    void listAuditLogs_keepsValidPagingUntouched() {
        stubAuditPage(21L, List.of(new SysConfigAudit()));

        controller.listAuditLogs(2, 10);

        Page<SysConfigAudit> query = captureQueryPage();
        assertEquals(2L, query.getCurrent());
        assertEquals(10L, query.getSize());
    }

    @Test
    void listAuditLogs_emptyResultStillCarriesPagingEnvelope() {
        stubAuditPage(0L, List.of());

        Result<PageResult<SysConfigAudit>> result = controller.listAuditLogs(3, 20);

        // list 为 null 会迫使前端到处防 null，这里统一落成空列表。
        assertEquals(List.of(), result.getData().getList());
        assertEquals(0L, result.getData().getTotal());
        assertEquals(3, result.getData().getPage());
    }
}
