package uno.acloud.file.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.file.infrastructure.entity.FileObjectRef;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileObjectRefMapper 集成测试 — 验证 OSS 对象引用计数的增删和生命周期状态转换。
 *
 * <p>核心场景：ON DUPLICATE KEY UPDATE 累加引用、递减到零后标记 PENDING_DELETE。</p>
 */
class FileObjectRefMapperIntegrationTest extends AbstractIntegrationTest {

    static { DB_NAME = "zxyz_file"; }

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private FileObjectRefMapper fileObjectRefMapper;

    /**
     * 对同一个 object_key 调用两次 incrementReference(1)，
     * 验证 ON DUPLICATE KEY UPDATE 将 ref_count 累加到 2。
     */
    @Test
    void incrementReferenceOnDuplicateKey() {
        String objectKey = "test-oss-object-" + System.nanoTime();

        // First insert — creates new row with ref_count = 1
        int rows1 = fileObjectRefMapper.incrementReference(objectKey, 1, "ACTIVE", "oss");
        assertEquals(1, rows1, "First insert should affect 1 row");

        // Second insert — ON DUPLICATE KEY UPDATE increments ref_count
        int rows2 = fileObjectRefMapper.incrementReference(objectKey, 1, "ACTIVE", "oss");
        // MySQL 语义：ON DUPLICATE KEY UPDATE 走更新路径时 affected rows = 2
        assertEquals(2, rows2, "Duplicate key update (increment path) affects 2 rows under MySQL semantics");

        // Verify final state
        FileObjectRef ref = fileObjectRefMapper.selectByKey(objectKey);
        assertNotNull(ref, "Object ref should exist after two increments");
        assertEquals(2, ref.getRefCount(), "ref_count should be 2 after two increments of 1");
        assertEquals("ACTIVE", ref.getDeleteStatus(), "Status should remain ACTIVE");
    }

    /**
     * 引用计数完整生命周期：increment 到 2 -> decrement 两次到 0 -> markPendingIfUnused。
     * 验证 ref_count 递减和 delete_status 从 ACTIVE 转为 PENDING_DELETE。
     */
    @Test
    void decrementAndMarkPendingLifecycle() {
        String objectKey = "test-oss-lifecycle-" + System.nanoTime();

        // Step 1: Insert with ref_count = 2
        fileObjectRefMapper.incrementReference(objectKey, 2, "ACTIVE", "oss");
        FileObjectRef initial = fileObjectRefMapper.selectByKey(objectKey);
        assertEquals(2, initial.getRefCount());
        assertEquals("ACTIVE", initial.getDeleteStatus());

        // Step 2: Decrement to 1
        int dec1 = fileObjectRefMapper.decrementReference(objectKey, 1, "ACTIVE");
        assertEquals(1, dec1, "Decrement should affect 1 row");
        FileObjectRef afterDec1 = fileObjectRefMapper.selectByKey(objectKey);
        assertEquals(1, afterDec1.getRefCount(), "ref_count should be 1 after first decrement");

        // Step 3: Decrement to 0
        int dec2 = fileObjectRefMapper.decrementReference(objectKey, 1, "ACTIVE");
        assertEquals(1, dec2, "Decrement should affect 1 row");
        FileObjectRef afterDec2 = fileObjectRefMapper.selectByKey(objectKey);
        assertEquals(0, afterDec2.getRefCount(), "ref_count should be 0 after second decrement");
        assertEquals("ACTIVE", afterDec2.getDeleteStatus(), "Status should still be ACTIVE at ref_count=0");

        // Step 4: Mark pending — should transition ACTIVE -> PENDING_DELETE when ref_count = 0
        int marked = fileObjectRefMapper.markPendingIfUnused(objectKey, "ACTIVE", "PENDING_DELETE");
        assertEquals(1, marked, "markPendingIfUnused should affect 1 row");

        FileObjectRef final_ = fileObjectRefMapper.selectByKey(objectKey);
        assertEquals(0, final_.getRefCount(), "ref_count should remain 0");
        assertEquals("PENDING_DELETE", final_.getDeleteStatus(), "Status should transition to PENDING_DELETE");
    }
}
