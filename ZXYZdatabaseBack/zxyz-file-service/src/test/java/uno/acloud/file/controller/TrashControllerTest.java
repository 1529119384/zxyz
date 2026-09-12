package uno.acloud.file.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.PageResult;
import uno.acloud.common.Result;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.vo.FileListItemVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回收站列表接口的分页契约（07-P0-2）。
 *
 * <p>控制器只做参数透传与结果包装，没有额外逻辑，所以这里刻意断言
 * **"页参数原样传到端口"** —— 如果哪天有人在控制器里把 page/pageSize 丢掉，
 * 只断言返回值的用例仍会绿，而这类回归恰恰最要命。
 */
@ExtendWith(MockitoExtension.class)
class TrashControllerTest {

    @Mock
    private FileQueryPort fileQueryPort;

    private TrashController trashController;

    @BeforeEach
    void setUp() {
        trashController = new TrashController(fileQueryPort);
    }

    @Test
    void listTrashFiles_delegatesPagingAndSpaceParamsToPort() {
        PageResult<FileListItemVO> paged = PageResult.of(2, 10, 25L, List.of(new FileListItemVO()));
        when(fileQueryPort.getRecycleList(eq(7L), eq(2), eq(9L), eq(1L), eq(2), eq(10)))
                .thenReturn(paged);

        Result<PageResult<FileListItemVO>> result = trashController.listTrashFiles(1L, 7L, 2, 9L, 2, 10);

        verify(fileQueryPort).getRecycleList(7L, 2, 9L, 1L, 2, 10);
        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertEquals(2, result.getData().getPage());
        assertEquals(10, result.getData().getPageSize());
        assertEquals(25L, result.getData().getTotal());
        assertEquals(1, result.getData().getList().size());
    }

    @Test
    void listTrashFiles_personalSpacePassesNullSpaceParams() {
        when(fileQueryPort.getRecycleList(eq(null), eq(null), eq(null), eq(1L), any(), any()))
                .thenReturn(PageResult.of(1, 20, 0L, List.of()));

        Result<PageResult<FileListItemVO>> result = trashController.listTrashFiles(1L, null, null, null, null, null);

        verify(fileQueryPort).getRecycleList(null, null, null, 1L, null, null);
        assertEquals(0L, result.getData().getTotal());
        assertEquals(List.of(), result.getData().getList());
    }

    @Test
    void listTrashFiles_wrapsPortResultWithoutReshaping() {
        PageResult<FileListItemVO> paged = PageResult.of(3, 5, 42L, List.of());
        when(fileQueryPort.getRecycleList(any(), any(), any(), any(), any(), any())).thenReturn(paged);

        Result<PageResult<FileListItemVO>> result = trashController.listTrashFiles(1L, 7L, 1, null, 3, 5);

        // 控制器不应自行改动端口给出的分页字段。
        assertEquals(3, result.getData().getPage());
        assertEquals(5, result.getData().getPageSize());
        assertEquals(42L, result.getData().getTotal());
    }
}
