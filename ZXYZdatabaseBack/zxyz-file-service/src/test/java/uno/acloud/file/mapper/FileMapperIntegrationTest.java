package uno.acloud.file.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.vo.FileSearchItemVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileMapper 集成测试 — 验证 MyBatis 注解 SQL 在真实 MySQL 上的行为。
 *
 * <p>使用 Testcontainers 启动 MySQL 8.0 + Redis 7，
 * Flyway 自动执行 V1__init_schema.sql 建表。</p>
 */
class FileMapperIntegrationTest extends AbstractIntegrationTest {

    static { DB_NAME = "zxyz_file"; }

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private FileMapper fileMapper;

    // ---- helper methods ----

    private Folder buildFolder(String name, Long parentId) {
        Folder folder = Folder.create();
        folder.setOriginalName(name);
        folder.setStorePath("/" + name);
        folder.setUploadUserId(1L);
        folder.setSpaceType(1);
        folder.setParentId(parentId);
        folder.setCreateTime(LocalDateTime.now());
        folder.setModifyTime(LocalDateTime.now());
        folder.setDeleted(0);
        return folder;
    }

    private FileItem buildFileItem(String name, Long parentId, long userId) {
        FileItem item = FileItem.create();
        item.setOriginalName(name);
        item.setUuidName("uuid-" + name);
        item.setCategory(1);
        item.setFileSize(1024L);
        item.setFileUrl("http://example.com/" + name);
        item.setStorePath("/" + name);
        item.setUploadUserId(userId);
        item.setSpaceType(1);
        item.setParentId(parentId);
        item.setCreateTime(LocalDateTime.now());
        item.setModifyTime(LocalDateTime.now());
        item.setDeleted(0);
        return item;
    }

    // ---- tests ----

    /**
     * 插入一个根文件夹，再插入一个子文件，
     * 通过 getFileNodesByParentId() 查询子节点并断言。
     */
    @Test
    void insertFolderAndListChildren() {
        // Insert root folder
        Folder root = buildFolder("root-folder", -1L);
        fileMapper.insertFolder(root);
        assertNotNull(root.getId(), "Root folder should get auto-generated ID");

        // Insert a file as child of root
        FileItem child = buildFileItem("test.txt", root.getId(), 1L);
        fileMapper.insertFileItem(child);
        assertNotNull(child.getId(), "File item should get auto-generated ID");

        // Query children of root
        List<FileNode> children = fileMapper.getFileNodesByParentId(root.getId());
        assertEquals(1, children.size(), "Root should have exactly one child");
        assertEquals("test.txt", children.get(0).getOriginalName());
        assertTrue(children.get(0) instanceof FileItem, "Child should be deserialized as FileItem");
        FileItem result = (FileItem) children.get(0);
        assertEquals("uuid-test.txt", result.getUuidName());
        assertEquals(1024L, result.getFileSize());
    }

    /**
     * 创建 3 级文件夹层级 (root -> sub -> leaf)，并在 sub 下放一个文件，
     * 调用 collectDescendantIds() 验证 WITH RECURSIVE CTE 能递归收集所有后代。
     */
    @Test
    void collectDescendantIdsRecursiveCTE() {
        // Level 1: root folder
        Folder root = buildFolder("root", -1L);
        fileMapper.insertFolder(root);

        // Level 2: sub folder under root
        Folder sub = buildFolder("sub", root.getId());
        fileMapper.insertFolder(sub);

        // Level 3: leaf folder under sub
        Folder leaf = buildFolder("leaf", sub.getId());
        fileMapper.insertFolder(leaf);

        // Also add a file directly under sub
        FileItem file = buildFileItem("doc.txt", sub.getId(), 1L);
        fileMapper.insertFileItem(file);

        // Collect all descendants starting from root
        List<Long> descendants = fileMapper.collectDescendantIds(List.of(root.getId()));

        // CTE includes the seed (root itself) + all recursive descendants
        assertTrue(descendants.contains(root.getId()), "Should contain root");
        assertTrue(descendants.contains(sub.getId()), "Should contain sub");
        assertTrue(descendants.contains(leaf.getId()), "Should contain leaf");
        assertTrue(descendants.contains(file.getId()), "Should contain file under sub");
        assertEquals(4, descendants.size(), "Should have 4 nodes total (root + sub + leaf + file)");
    }

    /**
     * 插入 5 个不同名称的文件，通过 searchByKeyword() 分页搜索，
     * 验证 LIKE 前缀匹配和 LIMIT/OFFSET 分页逻辑。
     */
    @Test
    void searchByKeywordWithPagination() {
        long userId = 42L;

        // Insert 5 files with various names, all owned by the same user
        fileMapper.insertFileItem(buildFileItem("alpha-report.pdf", -1L, userId));
        fileMapper.insertFileItem(buildFileItem("alpha-notes.txt", -1L, userId));
        fileMapper.insertFileItem(buildFileItem("beta-summary.doc", -1L, userId));
        fileMapper.insertFileItem(buildFileItem("alpha-draft.doc", -1L, userId));
        fileMapper.insertFileItem(buildFileItem("gamma-data.csv", -1L, userId));

        // Search "alpha" with pageSize=2, offset=0 — should return 2 results
        List<FileSearchItemVO> page1 = fileMapper.searchByKeyword(userId, "alpha", 2, 0);
        assertEquals(2, page1.size(), "First page should have 2 alpha results");
        assertTrue(page1.stream().allMatch(f -> f.getOriginalName().startsWith("alpha")),
                "All page1 results should start with 'alpha'");

        // Search "alpha" with pageSize=2, offset=2 — should return the remaining 1
        List<FileSearchItemVO> page2 = fileMapper.searchByKeyword(userId, "alpha", 2, 2);
        assertEquals(1, page2.size(), "Second page should have 1 alpha result");
        assertTrue(page2.get(0).getOriginalName().startsWith("alpha"));

        // Search "beta" — should match exactly 1 file
        List<FileSearchItemVO> betaResults = fileMapper.searchByKeyword(userId, "beta", 10, 0);
        assertEquals(1, betaResults.size());
        assertEquals("beta-summary.doc", betaResults.get(0).getOriginalName());

        // Search "nonexistent" — should return empty
        List<FileSearchItemVO> empty = fileMapper.searchByKeyword(userId, "nonexistent", 10, 0);
        assertTrue(empty.isEmpty(), "Search for non-existent prefix should return empty");
    }
}
