package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.FileNodeType;
import uno.acloud.common.FileSpaceType;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileDomainValidatorTest {

    @Mock
    private FileMapper fileMapper;

    private FileDomainValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FileDomainValidator(fileMapper);
    }

    // ---- validateInputName tests ----

    @Test
    void validateInputName_shouldAcceptValidName() {
        assertEquals("document.pdf", validator.validateInputName("document.pdf"));
    }

    @Test
    void validateInputName_shouldTrimWhitespace() {
        assertEquals("file.txt", validator.validateInputName("  file.txt  "));
    }

    @Test
    void validateInputName_shouldRejectNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateInputName(null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("文件名不能为空"));
    }

    @Test
    void validateInputName_shouldRejectEmpty() {
        assertThrows(BusinessException.class, () -> validator.validateInputName(""));
    }

    @Test
    void validateInputName_shouldRejectBlankAfterTrim() {
        assertThrows(BusinessException.class, () -> validator.validateInputName("   "));
    }

    @Test
    void validateInputName_shouldRejectSlash() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateInputName("path/file"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("路径分隔符"));
    }

    @Test
    void validateInputName_shouldRejectBackslash() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateInputName("path\\file"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void validateInputName_shouldRejectDot() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateInputName("."));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("."));
    }

    @Test
    void validateInputName_shouldRejectDoubleDot() {
        assertThrows(BusinessException.class, () -> validator.validateInputName(".."));
    }

    @Test
    void validateInputName_shouldRejectNameLongerThan100Chars() {
        String longName = "a".repeat(101);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateInputName(longName));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("长度不能超过 100"));
    }

    @Test
    void validateInputName_shouldAcceptNameExactly100Chars() {
        String name100 = "a".repeat(100);
        assertEquals(name100, validator.validateInputName(name100));
    }

    @Test
    void validateInputName_shouldAcceptChineseCharacters() {
        assertEquals("文件夹", validator.validateInputName("文件夹"));
    }

    // ---- normalizeFileIds tests ----

    @Test
    void normalizeFileIds_shouldRejectNull() {
        assertThrows(BusinessException.class, () -> validator.normalizeFileIds(null));
    }

    @Test
    void normalizeFileIds_shouldRejectEmpty() {
        assertThrows(BusinessException.class, () -> validator.normalizeFileIds(List.of()));
    }

    @Test
    void normalizeFileIds_shouldRejectNullElement() {
        assertThrows(BusinessException.class,
                () -> validator.normalizeFileIds(Arrays.asList(1L, null, 3L)));
    }

    @Test
    void normalizeFileIds_shouldRejectDuplicates() {
        assertThrows(BusinessException.class,
                () -> validator.normalizeFileIds(Arrays.asList(1L, 2L, 1L)));
    }

    @Test
    void normalizeFileIds_shouldReturnUniqueList() {
        List<Long> result = validator.normalizeFileIds(Arrays.asList(1L, 2L, 3L));
        assertEquals(3, result.size());
        assertEquals(List.of(1L, 2L, 3L), result);
    }

    // ---- requireNode tests ----

    @Test
    void requireNode_shouldThrowWhenFileIdIsNull() {
        assertThrows(BusinessException.class, () -> validator.requireNode(null));
    }

    @Test
    void requireNode_shouldThrowWhenNotFound() {
        when(fileMapper.getFileNodeById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.requireNode(999L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void requireNode_shouldReturnNodeWhenFound() {
        FileItem item = FileItem.create();
        item.setId(1L);
        when(fileMapper.getFileNodeById(1L)).thenReturn(item);

        assertNotNull(validator.requireNode(1L));
    }

    // ---- requireFolder tests ----

    @Test
    void requireFolder_shouldThrowWhenTargetIsFile() {
        FileItem item = FileItem.create();
        item.setId(1L);
        when(fileMapper.getFileNodeById(1L)).thenReturn(item);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.requireFolder(1L));

        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("目标不是文件夹"));
    }

    @Test
    void requireFolder_shouldReturnFolderWhenTargetIsFolder() {
        Folder folder = Folder.create();
        folder.setId(1L);
        when(fileMapper.getFileNodeById(1L)).thenReturn(folder);

        Folder result = validator.requireFolder(1L);
        assertNotNull(result);
    }

    // ---- requireActiveNode tests ----

    @Test
    void requireActiveNode_shouldThrowWhenDeleted() {
        FileItem item = FileItem.create();
        item.setId(1L);
        item.setDeleted(FileDeleteStatus.RECYCLE);
        when(fileMapper.getFileNodeById(1L)).thenReturn(item);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.requireActiveNode(1L));

        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
    }

    // ---- validateFolderTarget tests ----

    @Test
    void validateFolderTarget_shouldRejectDescendantMove() {
        Folder source = Folder.create();
        source.setId(1L);
        source.setStorePath("/parent/source");

        Folder target = Folder.create();
        target.setId(2L);
        target.setStorePath("/parent/source/sub");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateFolderTarget(source, target));

        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("自身或其子目录"));
    }

    @Test
    void validateFolderTarget_shouldRejectSelfMove() {
        Folder source = Folder.create();
        source.setId(1L);
        source.setStorePath("/parent/source");

        Folder target = Folder.create();
        target.setId(2L);
        target.setStorePath("/parent/source");

        assertThrows(BusinessException.class,
                () -> validator.validateFolderTarget(source, target));
    }

    @Test
    void validateFolderTarget_shouldPassForValidMove() {
        Folder source = Folder.create();
        source.setId(1L);
        source.setStorePath("/folderA");

        Folder target = Folder.create();
        target.setId(2L);
        target.setStorePath("/folderB");

        assertDoesNotThrow(() -> validator.validateFolderTarget(source, target));
    }

    @Test
    void validateFolderTarget_shouldPassForNonFolderNode() {
        FileItem file = FileItem.create();
        file.setId(1L);
        file.setStorePath("/file.txt");

        Folder target = Folder.create();
        target.setId(2L);
        target.setStorePath("/folder");

        // Non-folder nodes skip the descendant check
        assertDoesNotThrow(() -> validator.validateFolderTarget(file, target));
    }

    // ---- validateUserId tests ----

    @Test
    void validateUserId_shouldThrowForNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateUserId(null));
        assertEquals(ErrorCode.NO_LOGIN, ex.getErrorCode());
    }

    @Test
    void validateUserId_shouldPassForValidId() {
        assertDoesNotThrow(() -> validator.validateUserId(100L));
    }

    // ---- requireNodeForRename tests ----

    @Test
    void requireNodeForRename_shouldThrowForRecycledFile() {
        FileItem item = FileItem.create();
        item.setId(1L);
        item.setDeleted(FileDeleteStatus.RECYCLE);
        when(fileMapper.getFileNodeById(1L)).thenReturn(item);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.requireNodeForRename(1L));

        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("重命名"));
    }

    @Test
    void requireNodeForRename_shouldPassForActiveFile() {
        FileItem item = FileItem.create();
        item.setId(1L);
        item.setDeleted(FileDeleteStatus.NORMAL);
        when(fileMapper.getFileNodeById(1L)).thenReturn(item);

        assertNotNull(validator.requireNodeForRename(1L));
    }

    // ---- resolveAvailableName tests ----

    @Test
    void resolveAvailableName_shouldReturnOriginalNameWhenNotOccupied() {
        SpaceTarget target = new SpaceTarget(10L, FileSpaceType.TEAM, null);
        when(fileMapper.getActiveNamesByParentIdAndFileType(
                eq(-1L), eq(10L), eq(FileSpaceType.TEAM), isNull(),
                eq(FileNodeType.FOLDER), isNull()))
                .thenReturn(Collections.emptyList());

        String result = validator.resolveAvailableName(-1L, target, FileNodeType.FOLDER,
                "NewFolder", new HashSet<>(), null);

        assertEquals("NewFolder", result);
    }

    @Test
    void resolveAvailableName_shouldAppendSequenceWhenOccupied() {
        SpaceTarget target = new SpaceTarget(10L, FileSpaceType.TEAM, null);
        when(fileMapper.getActiveNamesByParentIdAndFileType(
                eq(-1L), eq(10L), eq(FileSpaceType.TEAM), isNull(),
                eq(FileNodeType.FOLDER), isNull()))
                .thenReturn(List.of("NewFolder"));

        String result = validator.resolveAvailableName(-1L, target, FileNodeType.FOLDER,
                "NewFolder", new HashSet<>(), null);

        assertEquals("NewFolder(1)", result);
    }

    @Test
    void resolveAvailableName_shouldAppendSequenceForFileWithExtension() {
        SpaceTarget target = new SpaceTarget(10L, FileSpaceType.TEAM, null);
        when(fileMapper.getActiveNamesByParentIdAndFileType(
                eq(-1L), eq(10L), eq(FileSpaceType.TEAM), isNull(),
                eq(FileNodeType.FILE), isNull()))
                .thenReturn(List.of("doc.pdf"));

        String result = validator.resolveAvailableName(-1L, target, FileNodeType.FILE,
                "doc.pdf", new HashSet<>(), null);

        assertEquals("doc(1).pdf", result);
    }

    @Test
    void resolveAvailableName_shouldThrowForNullTarget() {
        assertThrows(BusinessException.class,
                () -> validator.resolveAvailableName(-1L, null, FileNodeType.FOLDER, "test", new HashSet<>(), null));
    }

    @Test
    void resolveAvailableName_shouldThrowForInvalidFileType() {
        SpaceTarget target = new SpaceTarget(10L, FileSpaceType.TEAM, null);
        assertThrows(BusinessException.class,
                () -> validator.resolveAvailableName(-1L, target, 99, "test", new HashSet<>(), null));
    }

    // ---- requireNodes (batch) tests ----

    @Test
    void requireNodes_shouldBatchQueryAndReturnAll() {
        FileItem item1 = FileItem.create();
        item1.setId(1L);
        FileItem item2 = FileItem.create();
        item2.setId(2L);
        when(fileMapper.getFileNodesByIds(List.of(1L, 2L))).thenReturn(List.of(item1, item2));

        List<FileNode> result = validator.requireNodes(List.of(1L, 2L));

        assertEquals(2, result.size());
        verify(fileMapper, never()).getFileNodeById(anyLong());
    }

    @Test
    void requireNodes_shouldThrowWhenSomeIdsMissing() {
        FileItem item1 = FileItem.create();
        item1.setId(1L);
        when(fileMapper.getFileNodesByIds(List.of(1L, 99L))).thenReturn(List.of(item1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.requireNodes(List.of(1L, 99L)));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("99"));
    }

    @Test
    void requireNodes_shouldPreserveInputOrder() {
        FileItem item2 = FileItem.create();
        item2.setId(2L);
        FileItem item1 = FileItem.create();
        item1.setId(1L);
        when(fileMapper.getFileNodesByIds(List.of(2L, 1L))).thenReturn(List.of(item2, item1));

        List<FileNode> result = validator.requireNodes(List.of(2L, 1L));

        assertEquals(2L, result.get(0).getId());
        assertEquals(1L, result.get(1).getId());
    }

    // ---- requireMovableNodes (batch) tests ----

    @Test
    void requireMovableNodes_shouldReturnAllWhenActive() {
        FileItem item1 = FileItem.create();
        item1.setId(1L);
        item1.setDeleted(FileDeleteStatus.NORMAL);
        FileItem item2 = FileItem.create();
        item2.setId(2L);
        item2.setDeleted(FileDeleteStatus.NORMAL);
        when(fileMapper.getFileNodesByIds(List.of(1L, 2L))).thenReturn(List.of(item1, item2));

        List<FileNode> result = validator.requireMovableNodes(List.of(1L, 2L));

        assertEquals(2, result.size());
    }

    @Test
    void requireMovableNodes_shouldThrowWhenAnyNodeInactive() {
        FileItem active = FileItem.create();
        active.setId(1L);
        active.setDeleted(FileDeleteStatus.NORMAL);
        FileItem recycled = FileItem.create();
        recycled.setId(2L);
        recycled.setDeleted(FileDeleteStatus.RECYCLE);
        when(fileMapper.getFileNodesByIds(List.of(1L, 2L))).thenReturn(List.of(active, recycled));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.requireMovableNodes(List.of(1L, 2L)));

        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("2"));
    }
}
