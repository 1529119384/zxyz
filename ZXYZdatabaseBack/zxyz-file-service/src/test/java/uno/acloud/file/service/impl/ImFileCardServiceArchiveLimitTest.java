package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.vo.FileDownloadUrlVO;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F9（P3 N+1 / 无界递归）：IM 卡片归档的条目上限与递归深度上限。
 *
 * <h2>修复前的缺陷</h2>
 * <p>{@code resolveArchiveEntries} → {@code collectFolderArchiveEntries} 递归收集归档条目，
 * 其中<b>每个文件</b>都要一次 {@code getFileDownloadUrl} —— 该调用内部含
 * {@code objectExists}（OSS HEAD，<b>远程</b>）+ 预签名生成。三个问题叠加：</p>
 * <ol>
 *   <li><b>无条目上限</b>：分享数千文件的文件夹 = 数千次串行远程调用，请求必然超时；</li>
 *   <li><b>无深度上限</b>：递归没有深度护栏，数据存在环（脏数据/并发写入的自引用）会栈溢出；</li>
 *   <li><b>上限必须跨递归全局生效</b>：若每层各算各的，「1000 个文件夹 × 200 个文件」
 *       依然是 20 万次远程调用。</li>
 * </ol>
 *
 * <h2>为什么选择「明确拒绝」而不是「静默截断」</h2>
 * <p>截断会让用户拿到一个<b>少了文件</b>的压缩包且无从察觉 —— 归档场景下这是数据完整性问题，
 * 比一个可读的 400 错误严重得多。故超限时抛出带上限数值与替代方式的 {@code BusinessException}。</p>
 */
@ExtendWith(MockitoExtension.class)
class ImFileCardServiceArchiveLimitTest {

    @Mock
    private FileDomainValidator fileDomainValidator;
    @Mock
    private FileQueryPort fileQueryPort;
    @Mock
    private AuthServicePort authServicePort;

    private ImFileCardService service;

    @BeforeEach
    void setUp() {
        service = new ImFileCardService(fileDomainValidator, fileQueryPort, authServicePort);
    }

    private static FileInfoDTO file(long id, String name, long parentId) {
        FileInfoDTO dto = new FileInfoDTO();
        dto.setId(id);
        dto.setFileType(1);
        dto.setOriginalName(name);
        dto.setFileSize(10L);
        dto.setParentId(parentId);
        dto.setDeleted(0);
        dto.setUuidName("uuid-" + id);
        return dto;
    }

    private static FileInfoDTO folder(long id, String name, long parentId) {
        FileInfoDTO dto = new FileInfoDTO();
        dto.setId(id);
        dto.setFileType(0);
        dto.setOriginalName(name);
        dto.setParentId(parentId);
        dto.setDeleted(0);
        return dto;
    }

    private static FileDownloadUrlVO downloadUrl(long fileId) {
        return new FileDownloadUrlVO(fileId, "https://oss.example.com/" + fileId, true, "f" + fileId);
    }

    /**
     * 递归必须真的走下去（否则「上限」这件事无从谈起）：先证明小规模归档能正确收集。
     * <p>这同时是后两条「超限」用例的对照组 —— 若本用例也失败，说明是递归本身坏了而非上限逻辑。</p>
     */
    @Test
    void collectFolderEntries_recursesIntoNestedFolders() {
        FileInfoDTO root = folder(1L, "root", -1L);
        FileInfoDTO nested = folder(2L, "nested", 1L);
        FileInfoDTO leafFile = file(3L, "a.txt", 2L);

        when(fileQueryPort.getChildrenByParentIdWithDeleted(1L, 7L)).thenReturn(List.of(nested));
        when(fileQueryPort.getChildrenByParentIdWithDeleted(2L, 7L)).thenReturn(List.of(leafFile));
        when(fileQueryPort.getFileDownloadUrl(3L, 7L)).thenReturn(downloadUrl(3L));

        var entries = invokeArchiveEntries(List.of(root), 7L);

        assertEquals(1, entries.size());
        assertEquals("root/nested/a.txt", entries.get(0).getArchivePath(),
                "归档路径必须包含完整层级（证明递归真的走了两层）");
    }

    /**
     * F9 核心：条目数超过上限时必须抛可读的 {@code BusinessException}，
     * 且<b>在生成完全部条目之前</b>就停手（不得先把 1 万次远程调用跑完再报错）。
     */
    @Test
    void collectFolderEntries_rejectsWhenEntryCountExceedsLimit() {
        FileInfoDTO root = folder(1L, "big", -1L);
        // 超过 MAX_ARCHIVE_ENTRIES(200) 的扁平文件列表
        List<FileInfoDTO> many = new ArrayList<>();
        for (long i = 0; i < 260; i++) {
            many.add(file(1000L + i, "f" + i + ".txt", 1L));
        }
        when(fileQueryPort.getChildrenByParentIdWithDeleted(1L, 7L)).thenReturn(many);
        when(fileQueryPort.getFileDownloadUrl(anyLong(), eq(7L)))
                .thenAnswer(invocation -> downloadUrl(invocation.getArgument(0)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeArchiveEntries(List.of(root), 7L));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("200"),
                "错误信息必须给出上限数值，便于用户判断如何拆分；实际：" + ex.getMessage());
        // 关键：必须在上限处就停手，而不是先把 260 个全跑完
        verify(fileQueryPort, atMost(200)).getFileDownloadUrl(anyLong(), eq(7L));
    }

    /**
     * F9 深度上限：构造超过 100 层的嵌套，必须在达到上限时抛出可读错误，
     * 而不是无限递归导致 {@code StackOverflowError}。
     */
    @Test
    void collectFolderEntries_rejectsWhenNestingExceedsDepthLimit() {
        FileInfoDTO root = folder(1L, "level-0", -1L);
        // 每一层只有一个子文件夹，形成 150 层深链。
        // 用 lenient 是因为「深度上限（100）之后的那些层」本就不该被访问 ——
        // 正是本用例要证明的性质，不能因此报 UnnecessaryStubbing。
        for (long depth = 0; depth < 150; depth++) {
            long folderId = 2L + depth;
            FileInfoDTO child = folder(folderId, "level-" + (depth + 1), 1L + depth);
            lenient().when(fileQueryPort.getChildrenByParentIdWithDeleted(1L + depth, 7L)).thenReturn(List.of(child));
        }

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeArchiveEntries(List.of(root), 7L),
                "超过深度上限必须抛业务异常，而不是 StackOverflowError");

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("层"),
                "深度超限的错误信息必须说明原因；实际：" + ex.getMessage());
    }

    /**
     * 上限必须<b>跨递归全局</b>生效：多层文件夹各自不足上限、但累计超限时也必须拒绝。
     * <p>若实现把计数写成「每层独立」，本用例会通过而缺陷仍在（各层 150 < 200）。</p>
     */
    @Test
    void entryLimitAppliesAcrossAllNestedFoldersNotPerFolder() {
        FileInfoDTO root = folder(1L, "root", -1L);
        // 2 个子文件夹，各含 150 个文件（单层 150 < 200，累计 300 > 200）
        List<FileInfoDTO> subFolders = List.of(
                folder(10L, "sub0", 1L), folder(11L, "sub1", 1L));
        when(fileQueryPort.getChildrenByParentIdWithDeleted(1L, 7L)).thenReturn(subFolders);
        for (long i = 0; i < 2; i++) {
            List<FileInfoDTO> filesInSub = new ArrayList<>();
            for (long j = 0; j < 150; j++) {
                filesInSub.add(file(100L + i * 1000 + j, "f" + j + ".txt", 10L + i));
            }
            when(fileQueryPort.getChildrenByParentIdWithDeleted(10L + i, 7L)).thenReturn(filesInSub);
        }
        when(fileQueryPort.getFileDownloadUrl(anyLong(), eq(7L)))
                .thenAnswer(invocation -> downloadUrl(invocation.getArgument(0)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeArchiveEntries(List.of(root), 7L));

        assertTrue(ex.getMessage().contains("200"),
                "累计超限必须被拒绝（上限须跨递归全局生效，不能每层各算各的）；实际：" + ex.getMessage());
    }

    /**
     * 通过反射调用私有 {@code resolveArchiveEntries}：它需要 shareType/status/activeFiles，
     * 这些前置条件由 {@code createSnapshot}/{@code resolve} 负责推导，与本类要测的
     * 「归档条目上限」正交。直接测该方法能避免为前置链路铺大量无关桩。
     */
    private List<uno.acloud.file.vo.im.FileCardArchiveEntryVO> invokeArchiveEntries(
            List<FileInfoDTO> activeFiles, Long userId) {
        try {
            var method = ImFileCardService.class.getDeclaredMethod(
                    "resolveArchiveEntries", String.class, List.class, String.class, Long.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<uno.acloud.file.vo.im.FileCardArchiveEntryVO> result =
                    (List<uno.acloud.file.vo.im.FileCardArchiveEntryVO>) method.invoke(
                            service, "MULTI_FILE", activeFiles, "AVAILABLE", userId);
            return result;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法调用 resolveArchiveEntries（签名可能已变）", e);
        }
    }
}
