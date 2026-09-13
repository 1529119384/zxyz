package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 覆盖「上传成功后必须失效存储用量缓存」这一缺陷（用户报障：上传后容量条未及时刷新）。
 *
 * <p>根因：{@link StorageCacheService#invalidateAllStorageCaches()} 虽有实现但长期没有任何生产
 * 调用点，导致 file-service 侧 30s 用量缓存永不失效；project-service 侧的用量缓存则依赖
 * {@code file.resource.changed} 事件，而上传路径此前也从不发布事件。两条链路都要在此锁死。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileUploadPersistenceManagerTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileObjectReferenceManager fileObjectReferenceService;

    @Mock
    private UsageLedgerMapper usageLedgerMapper;

    @Mock
    private StorageCacheService storageCacheService;

    @Mock
    private FileResourceChangedPublisher fileResourceChangedPublisher;

    private FileUploadPersistenceManager manager;

    @BeforeEach
    void setUp() {
        manager = new FileUploadPersistenceManager(fileMapper, fileObjectReferenceService, usageLedgerMapper,
                storageCacheService, Optional.of(fileResourceChangedPublisher));
    }

    private FileItem newFileItem(Long id) {
        FileItem item = FileItem.create();
        item.setId(id);
        item.setUuidName("uuid-" + id);
        item.setStorageProvider("oss");
        item.setFileSize(2048L);
        item.setUploadUserId(1L);
        item.setTeamId(10L);
        item.setSpaceType(2);
        return item;
    }

    @Test
    void saveFileItem_shouldInvalidateStorageCachesAndPublishCreatedEvent() {
        FileItem item = newFileItem(77L);
        when(fileMapper.insertFileItem(item)).thenReturn(1);
        when(usageLedgerMapper.incrementWhenUnderLimit(anyString(), anyLong())).thenReturn(1);

        FileItem saved = manager.saveFileItem(item);

        assertSame(item, saved);
        // 上传改变了 SUM(file_size) 口径 ⇒ file-service 侧缓存必须失效
        verify(storageCacheService).invalidateAllStorageCaches();
        // 同时广播 file.resource.changed，驱动 project-service 侧配额缓存失效
        verify(fileResourceChangedPublisher)
                .publishByIds(FileUploadPersistenceManager.EVENT_TYPE_CREATED, List.of(77L));
    }

    @Test
    void saveFileItem_shouldNotTouchCachesWhenQuotaExceeded() {
        FileItem item = newFileItem(88L);
        when(fileMapper.insertFileItem(item)).thenReturn(1);
        when(usageLedgerMapper.incrementWhenUnderLimit(anyString(), anyLong())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> manager.saveFileItem(item));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());

        // 事务整体回滚 ⇒ 不得失效缓存、不得广播事件（否则会把未落库的文件通知出去）
        verifyNoInteractions(storageCacheService);
        verifyNoInteractions(fileResourceChangedPublisher);
    }

    @Test
    void saveFileItem_shouldStillInvalidateWhenPublisherAbsent() {
        // 未启用 MQ 的部署形态：缓存失效不能因此被跳过
        FileUploadPersistenceManager withoutPublisher = new FileUploadPersistenceManager(
                fileMapper, fileObjectReferenceService, usageLedgerMapper, storageCacheService, Optional.empty());
        FileItem item = newFileItem(99L);
        when(fileMapper.insertFileItem(item)).thenReturn(1);
        when(usageLedgerMapper.incrementWhenUnderLimit(anyString(), anyLong())).thenReturn(1);

        withoutPublisher.saveFileItem(item);

        verify(storageCacheService).invalidateAllStorageCaches();
    }

    @Test
    void saveFileItem_shouldSucceedEvenIfCacheInvalidationFails() {
        FileItem item = newFileItem(101L);
        when(fileMapper.insertFileItem(item)).thenReturn(1);
        when(usageLedgerMapper.incrementWhenUnderLimit(anyString(), anyLong())).thenReturn(1);
        doThrow(new IllegalStateException("redis down")).when(storageCacheService).invalidateAllStorageCaches();

        // 文件已落库是既成事实，Redis 抖动不能把一次成功的上传变成 500（缓存 30s 自然过期兜底）
        assertDoesNotThrow(() -> manager.saveFileItem(item));
    }
}
