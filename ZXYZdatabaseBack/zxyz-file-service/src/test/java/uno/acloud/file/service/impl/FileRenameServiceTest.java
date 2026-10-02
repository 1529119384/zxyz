package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileSpaceType;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.dto.RenameFileRequest;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.file.vo.RenameFileVO;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F6（P2 异常处理/一致性）：重命名的同级重名守卫。
 *
 * <h2>修复前的缺陷</h2>
 * <p>上传、复制、恢复三条路径都有「撞名自动加序号」策略（{@code resolveAvailableName}），
 * 唯独<b>重命名</b>没有。用户把文件改成同级已存在的名字时，唯一索引
 * {@code uk_file_node_scope_parent_type_active} 的 {@code DuplicateKeyException} 直接冒泡，
 * 用户收到全局兜底的 500 与原始 DB 异常文本 —— 既不可读，也与其余三条路径的行为不一致。</p>
 *
 * <h2>⚠️ 本类的语义前提：文件的「最终名」会补回原扩展名</h2>
 * <p>{@code buildRenamedOriginalName} 对 {@code FileItem} 的处理是
 * <b>{@code newName + "." + 原扩展名}</b>（既有行为，本次不改）：
 * 原名 {@code old.pdf}、输入 {@code report} ⇒ 最终落库 {@code report.pdf}；
 * 输入 {@code report.pdf} ⇒ 最终 {@code report.pdf.pdf}。
 * 因此断言必须针对<b>最终名</b>，否则会写出与实际行为不符的期望（本类初版即踩过）。
 * 拓展名补回只对文件生效，文件夹用输入名原样落地。</p>
 *
 * <h2>两个容易被写错的判定细节（本类的重点）</h2>
 * <ol>
 *   <li><b>必须按「最终文件名」查重</b>：只拿用户输入查重会漏掉
 *       「输入 report 撞上已存在的 report.pdf」。</li>
 *   <li><b>必须大小写不敏感</b>：{@code file_node} 的排序规则是 {@code utf8mb4_unicode_ci}，
 *       唯一索引的冲突判定同样是大小写不敏感的。按大小写敏感比对会让
 *       {@code Report.pdf} 撞 {@code report.pdf} 被放行，最终仍以 500 冒泡 —— 守卫形同虚设。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class FileRenameServiceTest {

    @Mock
    private FileMapper fileMapper;
    @Mock
    private StorageProviderRegistry registry;
    @Mock
    private FileDomainValidator fileDomainValidator;
    @Mock
    private FilePathResolver filePathResolver;
    @Mock
    private FileAccessGuard fileAccessGuardService;
    @Mock
    private FileOperationHelper helper;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private FileResourceChangedPublisher fileResourceChangedPublisher;

    private FileRenameService service;

    @BeforeEach
    void setUp() {
        service = new FileRenameService(fileMapper, registry, fileDomainValidator, filePathResolver,
                fileAccessGuardService, helper, transactionTemplate,
                Optional.of(fileResourceChangedPublisher));
        // 复刻 TransactionTemplate：直接执行回调，使事务内逻辑可被单测覆盖
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private FileItem activeFile(String name) {
        FileItem item = FileItem.create();
        item.setId(1L);
        item.setFileType(1);
        item.setOriginalName(name);
        item.setStorePath("/" + name);
        item.setParentId(100L);
        item.setUploadUserId(7L);
        item.setTeamId(null);
        item.setSpaceType(FileSpaceType.PERSONAL);
        item.setUuidName("files/uuid-1");
        return item;
    }

    private Folder activeFolder(String name) {
        Folder folder = Folder.create();
        folder.setId(2L);
        folder.setFileType(0);
        folder.setOriginalName(name);
        folder.setStorePath("/" + name);
        folder.setParentId(100L);
        folder.setUploadUserId(7L);
        folder.setTeamId(null);
        folder.setSpaceType(FileSpaceType.PERSONAL);
        return folder;
    }

    private RenameFileRequest renameRequest(long fileId, String newName) {
        RenameFileRequest request = new RenameFileRequest();
        request.setFileId(fileId);
        request.setNewName(newName);
        return request;
    }

    /** 只铺到「重名守卫」所需的桩（冲突用例不应铺后续落库相关的桩，否则 Strictness 会报多余桩）。 */
    private void givenUpToConflictCheck(FileItem node, String inputName) {
        when(fileDomainValidator.validateInputName(inputName)).thenReturn(inputName);
        when(fileDomainValidator.requireNodeForRename(node.getId())).thenReturn(node);
    }

    private void givenSiblingActiveNames(FileItem node, String... names) {
        when(fileMapper.getActiveNamesByParentIdAndFileType(
                node.getParentId(), null, FileSpaceType.PERSONAL, null, node.getFileType(), 7L))
                .thenReturn(List.of(names));
    }

    /**
     * 放行路径需要的最小桩：文件落库后 {@code renameFileNode} 会解析 provider 并
     * 在 afterCommit 里更新 content-disposition —— provider 为 null 会 NPE，与本条断言无关。
     */
    private void givenProviderAvailable(FileItem node) {
        when(registry.resolveForFile(node)).thenReturn(mock(uno.acloud.file.storage.StorageProvider.class));
    }

    // ==================== 冲突必须被拦下 ====================

    /**
     * 撞上同级活跃同名文件 ⇒ 必须抛可读的 {@code BusinessException}，
     * 而不是让唯一索引的 {@code DuplicateKeyException} 冒泡成 500。
     * <p>原名 {@code old.txt}、输入 {@code taken} ⇒ 最终名 {@code taken.txt}。</p>
     */
    @Test
    void renameFile_shouldRejectWhenActiveNameAlreadyExistsInSameParent() {
        FileItem node = activeFile("old.txt");
        givenUpToConflictCheck(node, "taken");
        givenSiblingActiveNames(node, "other.txt", "taken.txt");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.renameFile(renameRequest(1L, "taken"), 7L));

        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("同名文件/文件夹已存在"),
                "必须是可读的业务错误，不能是原始 DuplicateKeyException；实际：" + ex.getMessage());
        // 冲突时不得落库
        verify(fileMapper, never()).renameNodeById(any(), anyString(), anyString());
    }

    /**
     * 必须按<b>最终名</b>判冲突：输入 {@code report}（无扩展名）实际落库 {@code report.pdf}，
     * 若只拿输入 {@code report} 去比对就会漏判与已存在 {@code report.pdf} 的冲突。
     */
    @Test
    void renameFile_shouldDetectConflictAgainstFinalNameWithRestoredExtension() {
        FileItem node = activeFile("old.pdf");
        givenUpToConflictCheck(node, "report");
        givenSiblingActiveNames(node, "report.pdf");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.renameFile(renameRequest(1L, "report"), 7L));

        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("同名文件/文件夹已存在"),
                "必须按补回扩展名后的最终名 report.pdf 判冲突；实际：" + ex.getMessage());
    }

    /**
     * 大小写不敏感：{@code Report.pdf} 与已存在的 {@code report.pdf} 在
     * {@code utf8mb4_unicode_ci} 下是同一个唯一键 ⇒ 必须判为冲突，否则仍会 500 冒泡。
     */
    @Test
    void renameFile_shouldDetectConflictCaseInsensitively() {
        FileItem node = activeFile("old.pdf");
        givenUpToConflictCheck(node, "Report");
        givenSiblingActiveNames(node, "report.pdf");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.renameFile(renameRequest(1L, "Report"), 7L));

        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode(),
                "utf8mb4_unicode_ci 下 Report.pdf 与 report.pdf 是同一个唯一键，必须判冲突");
    }

    /** 文件夹不补扩展名、且与同名文件互不冲突（唯一键含 file_type 维度）。 */
    @Test
    void renameFolder_shouldRejectOnlyAgainstSameType() {
        Folder folder = activeFolder("old-dir");
        when(fileDomainValidator.validateInputName("taken-dir")).thenReturn("taken-dir");
        when(fileDomainValidator.requireNodeForRename(2L)).thenReturn(folder);
        when(fileMapper.getActiveNamesByParentIdAndFileType(100L, null, FileSpaceType.PERSONAL, null, 0, 7L))
                .thenReturn(List.of("taken-dir"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.renameFile(renameRequest(2L, "taken-dir"), 7L));

        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        // 关键：查的是 fileType = 0（文件夹），不能拿文件的名字空间去判
        verify(fileMapper).getActiveNamesByParentIdAndFileType(
                100L, null, FileSpaceType.PERSONAL, null, 0, 7L);
    }

    // ==================== 对照：不该拦的必须放行 ====================

    /** 目标名未被占用 ⇒ 正常重命名（守位不是「把重命名整个关掉」）。 */
    @Test
    void renameFile_shouldSucceedWhenTargetNameIsFree() {
        FileItem node = activeFile("old.txt");
        givenUpToConflictCheck(node, "brand-new");
        givenSiblingActiveNames(node, "other.txt");
        givenProviderAvailable(node);
        when(filePathResolver.buildStorePath(node.getParentId(), "brand-new.txt")).thenReturn("/brand-new.txt");
        when(fileMapper.renameNodeById(1L, "brand-new.txt", "/brand-new.txt")).thenReturn(1);

        RenameFileVO result = service.renameFile(renameRequest(1L, "brand-new"), 7L);

        assertEquals("brand-new.txt", result.getOriginalName(), "文件最终名 = 输入 + 原扩展名");
        verify(fileMapper).renameNodeById(1L, "brand-new.txt", "/brand-new.txt");
    }

    /**
     * 名字没变时必须放行 —— 否则「点保存但没改名」会被自己那一行命中而误判冲突。
     * <p>原名 {@code same.txt}、输入 {@code same} ⇒ 最终名仍为 {@code same.txt}，属无变化。</p>
     */
    @Test
    void renameFile_shouldAllowUnchangedNameWithoutQueryingConflicts() {
        FileItem node = activeFile("same.txt");
        givenUpToConflictCheck(node, "same");
        givenProviderAvailable(node);
        when(filePathResolver.buildStorePath(node.getParentId(), "same.txt")).thenReturn("/same.txt");
        when(fileMapper.renameNodeById(1L, "same.txt", "/same.txt")).thenReturn(1);

        assertDoesNotThrow(() -> service.renameFile(renameRequest(1L, "same"), 7L));

        // 名字未变 ⇒ 无需查重名（也避免自己命中自己）
        verify(fileMapper, never())
                .getActiveNamesByParentIdAndFileType(any(), any(), any(), any(), any(), any());
    }

    /** 仅大小写变化（{@code Same.txt} vs {@code same.txt}）也属无变化，不得误判冲突。 */
    @Test
    void renameFile_shouldAllowCaseOnlyChangeWithoutQueryingConflicts() {
        FileItem node = activeFile("same.txt");
        givenUpToConflictCheck(node, "Same");
        givenProviderAvailable(node);
        when(filePathResolver.buildStorePath(node.getParentId(), "Same.txt")).thenReturn("/Same.txt");
        when(fileMapper.renameNodeById(1L, "Same.txt", "/Same.txt")).thenReturn(1);

        assertDoesNotThrow(() -> service.renameFile(renameRequest(1L, "Same"), 7L));

        verify(fileMapper, never())
                .getActiveNamesByParentIdAndFileType(any(), any(), any(), any(), any(), any());
    }
}
