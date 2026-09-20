package uno.acloud.file.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.dto.PersonalStorageUsage;
import uno.acloud.dto.TeamStorageUsage;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.vo.FileSearchItemVO;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileMapper 集成测试 — 验证 MyBatis 注解 SQL 在真实 MySQL 上的行为。
 *
 * <p>使用 Testcontainers 启动 MySQL 8.4 + Redis 7，Flyway 自动执行
 * {@code V1__init_schema.sql} … {@code V7__add_usage_ledger.sql} 建表。</p>
 *
 * <h2>为什么覆盖得这么「啰嗦」</h2>
 * <p>本类被刻意写成「{@code FileMapper} 41 处内联 SQL 每处至少被调用一次」的形态，
 * 目的是给「把内联 SQL 迁移到 XML mapper」这类**纯搬家式重构**提供回归网。
 * 语句搬家最容易出的三类事故 —— statement id 解析不到、XML 里 {@code <} / {@code <>}
 * 未转义、结果映射 / 判别器丢失 —— 都会让对应断言立刻变红，且**能定位到是哪一种 SQL 形态断了**。
 * 分组即按 SQL 形态划分：单行 / 数组 {@code <script>} / 文本块 / {@code WITH RECURSIVE} /
 * 判别式结果映射 / {@code CASE id WHEN} 批量更新。</p>
 *
 * <h2>⚠️ 用例隔离（改本类前必读）</h2>
 * <p>{@code AbstractIntegrationTest} 的容器是 {@code static} 字段，同一 JVM 内被所有子类共享；
 * 本仓未开启 {@code testcontainers.reuse.enable}（无 {@code .testcontainers.properties}），
 * 容器按 JVM 复用一次。因此 <b>{@code file_node} 表在整个测试运行期内是脏的、跨用例共享的</b>。
 * 现有用例 {@code searchByKeywordWithPagination} 恰好用 {@code userId = 42} 做前缀搜索，
 * 一旦新用例往同一 userId 下插数据就会把它打红。</p>
 * <p>故本类约定：<b>凡不是按主键 / 父 id 限定的查询（关键词搜索、存储聚合、过期扫描），
 * 一律使用本用例独占的 {@code userId} / {@code teamId} / {@code projectId}</b>（见 {@link #nextId()}）；
 * 且所有插入行名都带唯一后缀（{@link #uniq(String)}），规避
 * {@code uk_file_node_scope_parent_type_active} 唯一键冲突。</p>
 */
class FileMapperIntegrationTest extends AbstractIntegrationTest {

    static { DB_NAME = "zxyz_file"; }

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private FileMapper fileMapper;

    // ==========================================================================
    // 用例隔离用的唯一 id 发放器
    // ==========================================================================

    /**
     * 从远大于真实业务数据（自增 id 通常 &lt; 10^7）的基址单调递增，
     * 保证与既有数据、以及同一 JVM 内其它测试类插入的数据互不干扰。
     */
    private static final AtomicLong SEQ =
            new AtomicLong(10_000_000_000L + Math.abs(System.nanoTime() % 1_000_000_000L) * 10L);

    private static long nextId() {
        return SEQ.incrementAndGet();
    }

    /** 生成带唯一后缀的名字，规避 {@code uk_file_node_scope_parent_type_active} 唯一键冲突。 */
    private static String uniq(String base) {
        return base + "-" + nextId();
    }

    // ==========================================================================
    // 构造 / 落库辅助
    // ==========================================================================

    private Folder buildFolder(String name, Long parentId) {
        return buildFolder(name, parentId, 1L, null, 1, null);
    }

    private Folder buildFolder(String name, Long parentId, long userId, Long teamId, int spaceType, Long projectId) {
        Folder folder = Folder.create();
        folder.setOriginalName(name);
        folder.setStorePath("/" + name);
        folder.setUploadUserId(userId);
        folder.setTeamId(teamId);
        folder.setSpaceType(spaceType);
        folder.setProjectId(projectId);
        folder.setParentId(parentId);
        folder.setCreateTime(LocalDateTime.now());
        folder.setModifyTime(LocalDateTime.now());
        folder.setDeleted(0);
        folder.setStorageProvider("oss");
        return folder;
    }

    private FileItem buildFileItem(String name, Long parentId, long userId) {
        return buildFileItem(name, parentId, userId, null, 1, null, 1024L);
    }

    private FileItem buildFileItem(String name, Long parentId, long userId, Long teamId, int spaceType,
                                   Long projectId, long fileSize) {
        FileItem item = FileItem.create();
        item.setOriginalName(name);
        item.setUuidName("uuid-" + name);
        item.setCategory(1);
        item.setFileSize(fileSize);
        item.setFileUrl("http://example.com/" + name);
        item.setStorePath("/" + name);
        item.setUploadUserId(userId);
        item.setTeamId(teamId);
        item.setSpaceType(spaceType);
        item.setProjectId(projectId);
        item.setParentId(parentId);
        item.setCreateTime(LocalDateTime.now());
        item.setModifyTime(LocalDateTime.now());
        item.setDeleted(0);
        item.setStorageProvider("oss");
        return item;
    }

    /** 落库并断言 {@code @Options(useGeneratedKeys)} 确实回填了主键。 */
    private Folder insertFolder(Folder folder) {
        fileMapper.insertFolder(folder);
        assertNotNull(folder.getId(), "insertFolder 必须回填自增 id（@Options useGeneratedKeys 丢了立刻变红）");
        return folder;
    }

    private FileItem insertFileItem(FileItem item) {
        fileMapper.insertFileItem(item);
        assertNotNull(item.getId(), "insertFileItem 必须回填自增 id");
        return item;
    }

    /** 本用例独占的个人空间根目录（parent_id = -1，space_type = 1，team_id = null）。 */
    private Folder personalRoot(long userId) {
        return insertFolder(buildFolder(uniq("root"), -1L, userId, null, 1, null));
    }

    // ==========================================================================
    // 1. 判别式结果映射（@Results + @TypeDiscriminator）
    // ==========================================================================

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
     * {@code getFileNodeById}：单行 + 判别式结果映射（file_type=1 → FileItem，file_type=0 → Folder）
     * + 公共列映射 + 子类扩展列（uuid_name / category / file_size / file_url）。
     */
    @Test
    void getFileNodeByIdDiscriminatesFileAndFolder() {
        long userId = nextId();
        Folder root = personalRoot(userId);

        String fileName = uniq("disc-file");
        FileItem file = insertFileItem(buildFileItem(fileName, root.getId(), userId, null, 1, null, 2048L));
        String folderName = uniq("disc-folder");
        Folder folder = insertFolder(buildFolder(folderName, root.getId(), userId, null, 1, null));

        // 文件分支：判别器切到 FileItem，且子类扩展列必须被映射
        FileNode fileNode = fileMapper.getFileNodeById(file.getId());
        assertNotNull(fileNode);
        assertInstanceOf(FileItem.class, fileNode, "file_type=1 必须被判别器映射为 FileItem");
        FileItem mapped = (FileItem) fileNode;
        assertEquals(fileName, mapped.getOriginalName());
        assertEquals("uuid-" + fileName, mapped.getUuidName(), "uuid_name 必须进子类映射");
        assertEquals(1, mapped.getCategory(), "category 必须进子类映射");
        assertEquals(2048L, mapped.getFileSize(), "file_size 必须进子类映射");
        assertEquals("http://example.com/" + fileName, mapped.getFileUrl(), "file_url 必须进子类映射");
        // 公共列映射
        assertEquals(root.getId(), mapped.getParentId());
        assertEquals(userId, mapped.getUploadUserId());
        assertEquals(1, mapped.getSpaceType());
        assertEquals(0, mapped.getDeleted());
        assertEquals("oss", mapped.getStorageProvider());
        assertNotNull(mapped.getCreateTime(), "create_time 必须被映射");
        assertNotNull(mapped.getModifyTime(), "modify_time 必须被映射");
        assertTrue(mapped.isFile(), "isFile() 依赖 fileType 映射正确");
        assertFalse(mapped.isFolder());

        // 文件夹分支：判别器切到 Folder
        FileNode folderNode = fileMapper.getFileNodeById(folder.getId());
        assertInstanceOf(Folder.class, folderNode, "file_type=0 必须被判别器映射为 Folder");
        assertEquals(folderName, folderNode.getOriginalName());
        assertTrue(folderNode.isFolder());

        // 不存在的 id → null（而不是抛异常）
        assertNull(fileMapper.getFileNodeById(nextId()), "不存在的 id 应返回 null");
    }

    /**
     * {@code getActiveFileNodeById}：与 {@link #getFileNodeByIdDiscriminatesFileAndFolder} 查同一张表、
     * 同一批列，只多一个 {@code deleted = 0} 谓词 —— 但**返回类型同样是抽象类 {@code FileNode}**。
     *
     * <p>因此这条用例是**判别式结果映射缺失的定点回归网**：本方法若没有与 {@code getFileNodeById}
     * 等价的 {@code @ResultMap("fileNodeResultMap")}（该 ResultMap 同时携带判别器），
     * MyBatis 会拿抽象类 {@code FileNode} 去实例化而失败，本用例即变红。</p>
     *
     * <p>真出问题时影响面是三个窄端点：{@code /api/internal/files/{id}/stream-info}、
     * {@code /stream}、{@code /share-download-url} —— 也就是说 <b>分享文件的下载链路</b>。
     * 主流写路径（{@code FileDomainValidator.requireNode}）走的是带判别器的
     * {@code getFileNodeById}，所以它坏掉时不会连带整站失效 —— 这正是它能长期潜伏的原因。</p>
     */
    @Test
    void getActiveFileNodeByIdMapsConcreteSubtype() {
        long userId = nextId();
        Folder root = personalRoot(userId);

        String fileName = uniq("active-file");
        FileItem file = insertFileItem(buildFileItem(fileName, root.getId(), userId, null, 1, null, 4096L));
        String folderName = uniq("active-folder");
        Folder folder = insertFolder(buildFolder(folderName, root.getId(), userId, null, 1, null));

        FileNode fileNode = fileMapper.getActiveFileNodeById(file.getId());
        assertNotNull(fileNode, "活跃文件必须能查出来");
        assertInstanceOf(FileItem.class, fileNode,
                "getActiveFileNodeById 必须返回具体子类：返回类型 FileNode 是抽象类，缺判别器会实例化失败");
        FileItem mapped = (FileItem) fileNode;
        assertEquals("uuid-" + fileName, mapped.getUuidName(), "uuid_name 必须进子类映射");
        assertEquals(4096L, mapped.getFileSize(), "file_size 必须进子类映射");
        assertEquals(root.getId(), mapped.getParentId());

        assertInstanceOf(Folder.class, fileMapper.getActiveFileNodeById(folder.getId()),
                "文件夹必须被映射为 Folder");

        // deleted = 0 谓词：软删后必须查不到
        fileMapper.logicalDeleteByIds(List.of(file.getId()), userId);
        assertNull(fileMapper.getActiveFileNodeById(file.getId()),
                "getActiveFileNodeById 必须带 deleted = 0 谓词，软删节点应查不到");
        // 反向对照：getFileNodeById 不过滤 deleted，软删后仍应查得到
        assertNotNull(fileMapper.getFileNodeById(file.getId()),
                "getFileNodeById 不带 deleted 谓词，软删节点仍应查得到");
    }

    // ==========================================================================
    // 2. 单行标量查询
    // ==========================================================================

    /** {@code countActiveChildren} / {@code getParentId}。 */
    @Test
    void countActiveChildrenAndGetParentId() {
        long userId = nextId();
        Folder root = personalRoot(userId);

        FileItem a = insertFileItem(buildFileItem(uniq("c-a"), root.getId(), userId, null, 1, null, 10L));
        insertFileItem(buildFileItem(uniq("c-b"), root.getId(), userId, null, 1, null, 20L));
        FileItem c = insertFileItem(buildFileItem(uniq("c-c"), root.getId(), userId, null, 1, null, 30L));

        assertEquals(3, fileMapper.countActiveChildren(root.getId()), "三个活跃子节点");

        fileMapper.logicalDeleteByIds(List.of(c.getId()), userId);
        assertEquals(2, fileMapper.countActiveChildren(root.getId()),
                "countActiveChildren 必须只数 deleted = 0");
        assertEquals(0, fileMapper.countActiveChildren(nextId()), "不存在的父 id 应返回 0");

        assertEquals(root.getId(), fileMapper.getParentId(a.getId()), "getParentId 应返回直接父 id");
        assertEquals(-1L, fileMapper.getParentId(root.getId()), "根节点 parent_id 为哨兵 -1");
        assertNull(fileMapper.getParentId(nextId()), "不存在的 id 应返回 null");
    }

    // ==========================================================================
    // 3. 批量 IN 查询（数组式 <script> + <foreach>）
    // ==========================================================================

    /** {@code getFileNodesByIds} / {@code getActiveFileNodesByIds} / {@code getOssKeysByIds}。 */
    @Test
    void batchSelectByIdsAndOssKeys() {
        long userId = nextId();
        Folder root = personalRoot(userId);

        FileItem f1 = insertFileItem(buildFileItem(uniq("b-1"), root.getId(), userId, null, 1, null, 11L));
        FileItem f2 = insertFileItem(buildFileItem(uniq("b-2"), root.getId(), userId, null, 1, null, 22L));
        Folder folder = insertFolder(buildFolder(uniq("b-folder"), root.getId(), userId, null, 1, null));

        List<Long> ids = List.of(f1.getId(), f2.getId(), folder.getId());

        // getFileNodesByIds：无 deleted 谓词
        List<FileNode> any = fileMapper.getFileNodesByIds(ids);
        assertEquals(3, any.size(), "getFileNodesByIds 应返回全部命中行");
        assertEquals(1, any.stream().filter(n -> n instanceof Folder).count(), "含 1 个文件夹");
        assertEquals(2, any.stream().filter(n -> n instanceof FileItem).count(), "含 2 个文件");

        // getActiveFileNodesByIds：deleted = 0 谓词
        assertEquals(3, fileMapper.getActiveFileNodesByIds(ids).size());

        // getOssKeysByIds：只取 file_type = 1 且 uuid_name 非空，且 deleted IN (0, 1)（软删仍算）
        fileMapper.logicalDeleteByIds(List.of(f2.getId()), userId);
        List<String> ossKeys = fileMapper.getOssKeysByIds(ids);
        assertEquals(2, ossKeys.size(), "软删(deleted=1)的文件的 oss key 也要取到，文件夹不含");
        assertTrue(ossKeys.contains("uuid-" + f1.getOriginalName()));
        assertTrue(ossKeys.contains("uuid-" + f2.getOriginalName()));

        // 彻底删除后（deleted = 2）不再取
        fileMapper.reallyDeleteByIds(List.of(f2.getId()), userId);
        List<String> afterPurge = fileMapper.getOssKeysByIds(ids);
        assertEquals(1, afterPurge.size(), "deleted=2 的行不在 deleted IN (0, 1) 内");
        assertTrue(afterPurge.contains("uuid-" + f1.getOriginalName()));

        // getActiveFileNodesByIds 也必须只返回 deleted = 0
        assertEquals(2, fileMapper.getActiveFileNodesByIds(ids).size(), "f2 已彻底删除 ⇒ 只剩 folder + f1");
    }

    // ==========================================================================
    // 4. choose 动态分支（个人 / 团队 / 项目）
    // ==========================================================================

    /**
     * {@code getFileNodesByParentId}（1 / 2 / 3 参 default 重载 + 5 参主方法）、
     * {@code getFileNodesByParentIdPaged}、{@code countByParentId} —— 三者共用同一段
     * {@code <choose>}，分页与计数必须同源。
     */
    @Test
    void parentListingChooseBranchesAndPaging() {
        // ---- 个人空间分支（teamId == null）：只看得见本人的行 ----
        long userA = nextId();
        long userB = nextId();
        Folder root = personalRoot(userA);

        insertFileItem(buildFileItem(uniq("p-a1"), root.getId(), userA, null, 1, null, 100L));
        insertFileItem(buildFileItem(uniq("p-a2"), root.getId(), userA, null, 1, null, 200L));
        // userB 的行 scope_key 不同，故可与 userA 的行同父目录共存
        insertFileItem(buildFileItem(uniq("p-b1"), root.getId(), userB, null, 1, null, 300L));
        insertFolder(buildFolder(uniq("p-f"), root.getId(), userA, null, 1, null));

        // 5 参主方法：userId = userA ⇒ 2 个文件 + 1 个文件夹
        List<FileNode> mine = fileMapper.getFileNodesByParentId(root.getId(), null, 1, null, userA);
        assertEquals(3, mine.size(), "个人空间分支应只返回 upload_user_id = userA 的行");
        assertTrue(mine.stream().allMatch(n -> n.getUploadUserId() == userA));

        // 3 参 default 重载 → teamId=null, spaceType=null, projectId=null, userId=userA
        assertEquals(3, fileMapper.getFileNodesByParentId(root.getId(), null, userA).size());
        // 2 参 default 重载 → 内层把 userId 置 null
        //   （`#{userId} IS NULL OR upload_user_id = #{userId}` 的第一分支命中，不再按人过滤）
        assertEquals(4, fileMapper.getFileNodesByParentId(root.getId(), null).size(),
                "userId 传 null 时个人空间分支不再按人过滤");
        // 1 参 default 重载
        assertEquals(4, fileMapper.getFileNodesByParentId(root.getId()).size());

        // 分页：ORDER BY file_type DESC, original_name ASC ⇒ 文件在前、文件夹在后
        List<FileNode> page = fileMapper.getFileNodesByParentIdPaged(root.getId(), null, 1, null, userA, 2, 0);
        assertEquals(2, page.size(), "limit=2 应只返回 2 行");
        assertEquals(List.of(1, 1), page.stream().map(FileNode::getFileType).collect(Collectors.toList()),
                "file_type DESC ⇒ 文件(1) 排在文件夹(0) 之前");

        // 按同一排序规则推导期望顺序，再逐页翻完，拼起来必须与一次性查询完全一致
        List<String> expectedOrder = mine.stream()
                .sorted((x, y) -> {
                    int byType = Integer.compare(y.getFileType(), x.getFileType());
                    return byType != 0 ? byType : x.getOriginalName().compareTo(y.getOriginalName());
                })
                .map(FileNode::getOriginalName)
                .collect(Collectors.toList());

        List<String> pagedAll = new ArrayList<>();
        for (int offset = 0; offset < expectedOrder.size(); offset += 2) {
            fileMapper.getFileNodesByParentIdPaged(root.getId(), null, 1, null, userA, 2, offset)
                    .forEach(n -> pagedAll.add(n.getOriginalName()));
        }
        assertEquals(expectedOrder, pagedAll,
                "分页拼接结果必须等于 ORDER BY file_type DESC, original_name ASC 的全量顺序");

        // countByParentId 必须与上面同一段 WHERE
        assertEquals(3, fileMapper.countByParentId(root.getId(), null, 1, null, userA),
                "countByParentId 与 getFileNodesByParentId 必须同源");

        // ---- 团队空间分支（teamId != null）----
        long teamId = nextId();
        Folder teamRoot = insertFolder(buildFolder(uniq("t-root"), -1L, userA, teamId, 2, null));
        insertFileItem(buildFileItem(uniq("t-1"), teamRoot.getId(), userA, teamId, 2, null, 500L));
        insertFileItem(buildFileItem(uniq("t-2"), teamRoot.getId(), userB, teamId, 2, null, 600L));

        List<FileNode> teamRows = fileMapper.getFileNodesByParentId(teamRoot.getId(), teamId, null, null, null);
        assertEquals(2, teamRows.size(), "团队分支只按 team_id 过滤，不看 upload_user_id");
        assertEquals(2, fileMapper.countByParentId(teamRoot.getId(), teamId, null, null, null));
        assertEquals(1, fileMapper.getFileNodesByParentIdPaged(teamRoot.getId(), teamId, null, null, null, 1, 0).size());

        // ---- 项目空间分支（spaceType == 3）----
        long projectId = nextId();
        Folder projectRoot = insertFolder(buildFolder(uniq("pr-root"), -1L, userA, teamId, 3, projectId));
        insertFileItem(buildFileItem(uniq("pr-1"), projectRoot.getId(), userA, teamId, 3, projectId, 700L));
        insertFileItem(buildFileItem(uniq("pr-2"), projectRoot.getId(), userB, teamId, 3, projectId, 800L));

        List<FileNode> projectRows = fileMapper.getFileNodesByParentId(projectRoot.getId(), null, 3, projectId, null);
        assertEquals(2, projectRows.size(), "项目分支按 space_type = 3 AND project_id 过滤");
        assertEquals(2, fileMapper.countByParentId(projectRoot.getId(), null, 3, projectId, null));
        assertEquals(2, fileMapper.getFileNodesByParentIdPaged(projectRoot.getId(), null, 3, projectId, null, 10, 0).size());

        // 反向：另一个 project_id 不应对本项目计数有贡献
        assertEquals(0, fileMapper.countByParentId(projectRoot.getId(), null, 3, nextId(), null));
    }

    /** {@code getActiveNamesByParentIdAndFileType}：返回活跃同名候选，供重名检测使用。 */
    @Test
    void activeNamesByParentIdAndFileType() {
        long userId = nextId();
        Folder root = personalRoot(userId);

        String fileA = uniq("n-file");
        String fileB = uniq("n-file");
        FileItem a = insertFileItem(buildFileItem(fileA, root.getId(), userId, null, 1, null, 1L));
        insertFileItem(buildFileItem(fileB, root.getId(), userId, null, 1, null, 1L));
        insertFolder(buildFolder(uniq("n-folder"), root.getId(), userId, null, 1, null));

        // fileType = 1 ⇒ 只返回文件
        List<String> fileNames = fileMapper.getActiveNamesByParentIdAndFileType(
                root.getId(), null, 1, null, 1, userId);
        assertEquals(2, fileNames.size(), "fileType=1 只应返回文件");
        assertTrue(fileNames.contains(fileA));
        assertTrue(fileNames.contains(fileB));

        // fileType = 0 ⇒ 只返回文件夹
        assertEquals(1, fileMapper.getActiveNamesByParentIdAndFileType(root.getId(), null, 1, null, 0, userId).size());

        // 软删后不再作为重名候选
        fileMapper.logicalDeleteByIds(List.of(a.getId()), userId);
        List<String> afterDelete = fileMapper.getActiveNamesByParentIdAndFileType(
                root.getId(), null, 1, null, 1, userId);
        assertEquals(1, afterDelete.size(), "已软删的名字不应再作为重名候选（deleted = 0 谓词丢了会变红）");
        assertFalse(afterDelete.contains(fileA));

        // teamId != null 分支
        long teamId = nextId();
        Folder teamRoot = insertFolder(buildFolder(uniq("n-troot"), -1L, userId, teamId, 2, null));
        String teamFileName = uniq("n-team");
        insertFileItem(buildFileItem(teamFileName, teamRoot.getId(), userId, teamId, 2, null, 1L));
        assertEquals(List.of(teamFileName),
                fileMapper.getActiveNamesByParentIdAndFileType(teamRoot.getId(), teamId, null, null, 1, null));
    }

    // ==========================================================================
    // 5. 含已删除节点的子节点查询（分享 / 回收站链路）
    // ==========================================================================

    /** {@code getChildrenByParentIdWithDeleted}（含 default 重载）与两个 share 变体。 */
    @Test
    void childrenWithDeletedVariants() {
        long userId = nextId();
        Folder root = personalRoot(userId);
        Folder sub = insertFolder(buildFolder(uniq("w-sub"), root.getId(), userId, null, 1, null));

        FileItem kept = insertFileItem(buildFileItem(uniq("w-kept"), sub.getId(), userId, null, 1, null, 1L));
        FileItem removed = insertFileItem(buildFileItem(uniq("w-removed"), sub.getId(), userId, null, 1, null, 1L));
        fileMapper.logicalDeleteByIds(List.of(removed.getId()), userId);

        // getChildrenByParentIdWithDeleted(parentId, teamId, userId)：无 deleted 谓词
        List<FileNode> all = fileMapper.getChildrenByParentIdWithDeleted(sub.getId(), null, userId);
        assertEquals(2, all.size(), "必须连软删的一起返回（回收站视图依赖）");
        assertEquals(1, all.stream().filter(n -> !n.isActive()).count(), "恰有 1 行非活跃");

        // default 重载 → teamId = null, userId = null
        assertEquals(2, fileMapper.getChildrenByParentIdWithDeleted(sub.getId()).size());

        // 限定 userId 后不返回别人的行
        assertEquals(0, fileMapper.getChildrenByParentIdWithDeleted(sub.getId(), null, nextId()).size(),
                "个人空间分支的 userId 过滤必须生效");

        // 团队分支：只按 team_id 过滤
        long teamId = nextId();
        Folder teamRoot = insertFolder(buildFolder(uniq("w-troot"), -1L, userId, teamId, 2, null));
        insertFileItem(buildFileItem(uniq("w-t1"), teamRoot.getId(), userId, teamId, 2, null, 1L));
        assertEquals(1, fileMapper.getChildrenByParentIdWithDeleted(teamRoot.getId(), teamId, null).size());

        // getShareChildrenByParentIdWithDeleted：不区分空间，也不过滤 deleted
        List<FileNode> shareChildren = fileMapper.getShareChildrenByParentIdWithDeleted(sub.getId());
        assertEquals(2, shareChildren.size(), "分享链路必须能取到已删除的子节点");
        assertTrue(shareChildren.stream().anyMatch(n -> n.getId().equals(kept.getId())));
        assertTrue(shareChildren.stream().anyMatch(n -> n.getId().equals(removed.getId())));

        // getShareChildrenByParentIdsWithDeleted：<foreach> 多父 id
        List<FileNode> multi = fileMapper.getShareChildrenByParentIdsWithDeleted(List.of(sub.getId(), teamRoot.getId()));
        assertEquals(3, multi.size(), "两个父目录的子节点应合并返回（2 + 1）");
        assertEquals(2, multi.stream().map(FileNode::getParentId).distinct().count(), "来自 2 个父目录");
    }

    // ==========================================================================
    // 6. 回收站分页与其「孪生」计数（源码注释要求 WHERE 逐字一致）
    // ==========================================================================

    /**
     * {@code getFileNodesInRecycleBinPaged} ↔ {@code countFileNodesInRecycleBin}。
     *
     * <p>{@code FileMapper} 源码注释明确要求两处 WHERE 逐字一致（"否则分页器会出现总条数与
     * 实际翻页结果对不上的静默错位"）。本用例把「分页结果数」与「计数」**在同一状态下交叉断言**，
     * 使任何一侧被单独改动都立刻暴露。</p>
     */
    @Test
    void recycleBinPagedAndCountStayConsistent() {
        long userId = nextId();
        Folder root = personalRoot(userId);
        Folder sub = insertFolder(buildFolder(uniq("r-sub"), root.getId(), userId, null, 1, null));
        Folder leaf = insertFolder(buildFolder(uniq("r-leaf"), sub.getId(), userId, null, 1, null));
        FileItem onlyChild = insertFileItem(buildFileItem(uniq("r-child"), sub.getId(), userId, null, 1, null, 1L));

        // 子节点软删、祖先仍活跃 ⇒ leaf 与 onlyChild 都是"回收站根"，应可见
        fileMapper.logicalDeleteByIds(List.of(onlyChild.getId(), leaf.getId()), userId);

        assertEquals(2, fileMapper.getFileNodesInRecycleBinPaged(null, null, null, userId, 10, 0).size(),
                "回收站根：软删的子文件 + 软删的叶子目录");
        assertEquals(2, fileMapper.countFileNodesInRecycleBin(null, null, null, userId),
                "countFileNodesInRecycleBin 必须与 getFileNodesInRecycleBinPaged 同源");
        // 分页切片的每个 offset 都必须落在计数范围内
        assertEquals(1, fileMapper.getFileNodesInRecycleBinPaged(null, null, null, userId, 1, 0).size());
        assertEquals(1, fileMapper.getFileNodesInRecycleBinPaged(null, null, null, userId, 1, 1).size());
        assertEquals(0, fileMapper.getFileNodesInRecycleBinPaged(null, null, null, userId, 1, 2).size(),
                "offset 超出总数必须返回空");

        // 祖先也软删 ⇒ NOT EXISTS 子查询使其从回收站根中消失
        fileMapper.logicalDeleteByIds(List.of(sub.getId()), userId);
        assertEquals(1, fileMapper.countFileNodesInRecycleBin(null, null, null, userId),
                "祖先被软删后其子孙不再算回收站根（NOT EXISTS 谓词丢了立刻变红）");
        assertEquals(1, fileMapper.getFileNodesInRecycleBinPaged(null, null, null, userId, 10, 0).size());

        // 他人的 userId 看不到本用例的内容
        assertEquals(0, fileMapper.countFileNodesInRecycleBin(null, null, null, nextId()),
                "upload_user_id 过滤必须生效");

        // 团队空间分支
        long teamId = nextId();
        Folder teamRoot = insertFolder(buildFolder(uniq("r-troot"), -1L, userId, teamId, 2, null));
        FileItem teamChild = insertFileItem(buildFileItem(uniq("r-tchild"), teamRoot.getId(), userId, teamId, 2, null, 1L));
        fileMapper.logicalDeleteByIds(List.of(teamChild.getId()), userId);
        assertEquals(1, fileMapper.countFileNodesInRecycleBin(teamId, null, null, null), "团队分支只看 team_id");
        assertEquals(1, fileMapper.getFileNodesInRecycleBinPaged(teamId, null, null, null, 10, 0).size());

        // 项目空间分支
        long projectId = nextId();
        Folder projectRoot = insertFolder(buildFolder(uniq("r-prroot"), -1L, userId, teamId, 3, projectId));
        FileItem projectChild = insertFileItem(
                buildFileItem(uniq("r-prchild"), projectRoot.getId(), userId, teamId, 3, projectId, 1L));
        fileMapper.logicalDeleteByIds(List.of(projectChild.getId()), userId);
        assertEquals(1, fileMapper.countFileNodesInRecycleBin(null, 3, projectId, null),
                "项目分支只看 space_type = 3 AND project_id");
    }

    // ==========================================================================
    // 7. WITH RECURSIVE 递归 CTE
    // ==========================================================================

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
     * {@code collectDescendantNodes}：另一条递归 CTE，与 {@code collectDescendantIds} 有两处关键语义差异 ——
     * <ul>
     *   <li>种子是 {@code parent_id IN (...)}（收集**子节点**，不含传入的父节点自身），
     *       而 {@code collectDescendantIds} 的种子是 {@code id IN (...)}（含自身）；</li>
     *   <li>种子与递归步都限定 {@code deleted = 0}，而 {@code collectDescendantIds} 是
     *       {@code deleted IN (0, 1)}。</li>
     * </ul>
     */
    @Test
    void collectDescendantNodesRecursiveCTE() {
        long userId = nextId();
        Folder root = personalRoot(userId);
        Folder sub = insertFolder(buildFolder(uniq("d-sub"), root.getId(), userId, null, 1, null));
        Folder leaf = insertFolder(buildFolder(uniq("d-leaf"), sub.getId(), userId, null, 1, null));
        FileItem underSub = insertFileItem(buildFileItem(uniq("d-file"), sub.getId(), userId, null, 1, null, 5L));
        FileItem removed = insertFileItem(buildFileItem(uniq("d-removed"), leaf.getId(), userId, null, 1, null, 5L));
        fileMapper.logicalDeleteByIds(List.of(removed.getId()), userId);

        List<FileNode> nodes = fileMapper.collectDescendantNodes(List.of(root.getId()));
        assertEquals(3, nodes.size(), "sub + leaf + file，且不含 root 自身");
        assertFalse(nodes.stream().anyMatch(n -> n.getId().equals(root.getId())), "不含传入的父节点自身");
        assertTrue(nodes.stream().anyMatch(n -> n.getId().equals(sub.getId())));
        assertTrue(nodes.stream().anyMatch(n -> n.getId().equals(leaf.getId())));
        assertTrue(nodes.stream().anyMatch(n -> n.getId().equals(underSub.getId())));
        assertFalse(nodes.stream().anyMatch(n -> n.getId().equals(removed.getId())),
                "deleted = 0 谓词：软删节点不应出现");
        // 结果映射必须带判别器（文件返回 FileItem，文件夹返回 Folder）
        assertEquals(1, nodes.stream().filter(n -> n instanceof FileItem).count());
        assertEquals(2, nodes.stream().filter(n -> n instanceof Folder).count());

        // 多父 id 的 <foreach>
        assertEquals(2, fileMapper.collectDescendantNodes(List.of(sub.getId(), leaf.getId())).size(),
                "sub 的子节点(leaf, file) + leaf 的子节点(已软删被排除) ⇒ 2");
    }

    // ==========================================================================
    // 8. 关键词搜索与计数
    // ==========================================================================

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

    /**
     * {@code countByKeyword}（4 参主方法 + 3 参 default 重载）与 {@code searchByKeyword}
     * （内联 {@code @Results} 把 {@code team_id AS teamId} 等别名列映射进 VO）。
     */
    @Test
    void countByKeywordMatchesSearchAndMapsInlineResults() {
        long userId = nextId();
        String prefix = uniq("kw");
        Folder root = personalRoot(userId);

        insertFileItem(buildFileItem(prefix + "-1", root.getId(), userId, null, 1, null, 111L));
        insertFileItem(buildFileItem(prefix + "-2", root.getId(), userId, null, 1, null, 222L));

        // 4 参主方法，teamId = null ⇒ 个人空间分支
        assertEquals(2, fileMapper.countByKeyword(userId, null, prefix));
        // 3 参 default 重载 → 内层置 teamId = null
        assertEquals(2, fileMapper.countByKeyword(userId, prefix));

        List<FileSearchItemVO> hits = fileMapper.searchByKeyword(userId, null, prefix, 10, 0);
        assertEquals(2, hits.size());
        // 内联 @Results 必须把各列都映射进 VO
        for (FileSearchItemVO vo : hits) {
            assertNotNull(vo.getId());
            assertEquals(1, vo.getFileType());
            assertTrue(vo.getOriginalName().startsWith(prefix));
            assertEquals(1, vo.getCategory());
            assertNotNull(vo.getFileSize());
            assertEquals(root.getId(), vo.getParentId());
            assertNull(vo.getTeamId(), "个人空间的 team_id 为 NULL，别名列映射后仍应为 null");
            assertNotNull(vo.getCreateTime());
            assertNotNull(vo.getModifyTime());
        }

        // 团队分支：team_id 必须被别名映射出来
        long teamId = nextId();
        Folder teamRoot = insertFolder(buildFolder(uniq("kw-troot"), -1L, userId, teamId, 2, null));
        String teamPrefix = uniq("kwt");
        insertFileItem(buildFileItem(teamPrefix + "-x", teamRoot.getId(), userId, teamId, 2, null, 333L));
        assertEquals(1, fileMapper.countByKeyword(userId, teamId, teamPrefix));
        FileSearchItemVO teamVo = fileMapper.searchByKeyword(userId, teamId, teamPrefix, 10, 0).get(0);
        assertEquals(teamId, teamVo.getTeamId(), "team_id AS teamId 别名必须映射到 VO.teamId");

        // 前缀匹配：不是前缀就不算
        assertEquals(0, fileMapper.countByKeyword(userId, null, "-1"));
    }

    // ==========================================================================
    // 9. 重命名 / 移动 / 存储路径改写（单行 UPDATE）
    // ==========================================================================

    /** {@code renameNodeById} / {@code moveNodeById} / {@code updateStorePathById} / {@code updateStorePathAndSpaceById}。 */
    @Test
    void singleRowUpdatesForRenameMoveAndStorePath() {
        long userId = nextId();
        Folder root = personalRoot(userId);
        Folder target = insertFolder(buildFolder(uniq("u-target"), root.getId(), userId, null, 1, null));
        FileItem file = insertFileItem(buildFileItem(uniq("u-file"), root.getId(), userId, null, 1, null, 1L));

        // renameNodeById：只改 original_name / store_path
        // 注：`modify_time = NOW()` 的存在不做跨时区时序断言（DB NOW() 与 JVM LocalDateTime 比较会假红），
        //     仅断言该列被写成了非空值。
        String newName = uniq("u-renamed");
        assertEquals(1, fileMapper.renameNodeById(file.getId(), newName, "/" + newName));
        FileItem afterRename = (FileItem) fileMapper.getFileNodeById(file.getId());
        assertEquals(newName, afterRename.getOriginalName());
        assertEquals("/" + newName, afterRename.getStorePath());
        assertNotNull(afterRename.getModifyTime(), "modify_time 必须非空（NOW() 写了值）");
        assertEquals(0, fileMapper.renameNodeById(nextId(), "nope", "/nope"), "不存在的 id 影响 0 行");

        // moveNodeById：改 parent_id 与空间三列
        long teamId = nextId();
        long projectId = nextId();
        String movedName = uniq("u-moved");
        assertEquals(1, fileMapper.moveNodeById(file.getId(), movedName, target.getId(),
                "/" + movedName, teamId, 3, projectId));
        FileItem afterMove = (FileItem) fileMapper.getFileNodeById(file.getId());
        assertEquals(target.getId(), afterMove.getParentId());
        assertEquals(movedName, afterMove.getOriginalName());
        assertEquals(teamId, afterMove.getTeamId());
        assertEquals(3, afterMove.getSpaceType());
        assertEquals(projectId, afterMove.getProjectId());

        // updateStorePathById：只改 store_path
        assertEquals(1, fileMapper.updateStorePathById(file.getId(), "/only-path"));
        FileNode afterPath = fileMapper.getFileNodeById(file.getId());
        assertEquals("/only-path", afterPath.getStorePath());
        assertEquals(teamId, afterPath.getTeamId(), "不应动 team_id");

        // updateStorePathAndSpaceById：store_path + 空间三列
        long teamId2 = nextId();
        long projectId2 = nextId();
        assertEquals(1, fileMapper.updateStorePathAndSpaceById(file.getId(), "/path-and-space", teamId2, 2, projectId2));
        FileNode afterSpace = fileMapper.getFileNodeById(file.getId());
        assertEquals("/path-and-space", afterSpace.getStorePath());
        assertEquals(teamId2, afterSpace.getTeamId());
        assertEquals(2, afterSpace.getSpaceType());
        assertEquals(projectId2, afterSpace.getProjectId());
        assertEquals(movedName, afterSpace.getOriginalName(), "不应动 original_name");
    }

    /**
     * {@code renameDescendantStorePaths}：按前缀批量改写 store_path。
     *
     * <p>SQL 里的 {@code CONCAT(#{oldPrefix}, '/%')} 意味着**前缀本身那一行不会被改**；
     * {@code SUBSTRING(store_path, LENGTH(#{oldPrefix}) + 1)} 是按字节截断。</p>
     */
    @Test
    void renameDescendantStorePathsByPrefix() {
        long userId = nextId();
        String tag = uniq("rp");
        String oldPrefix = "/" + tag;

        Folder base = insertFolder(buildFolder(tag, -1L, userId, null, 1, null));
        assertEquals(oldPrefix, base.getStorePath(), "断言前置：base 的 store_path 恰为 oldPrefix（无斜杠后缀）");

        Folder child = insertFolder(buildFolder(tag + "-child", base.getId(), userId, null, 1, null));
        assertEquals(1, fileMapper.updateStorePathById(child.getId(), oldPrefix + "/child"));

        Folder grand = insertFolder(buildFolder(tag + "-grand", child.getId(), userId, null, 1, null));
        assertEquals(1, fileMapper.updateStorePathById(grand.getId(), oldPrefix + "/child/grand"));

        String newPrefix = "/" + uniq("rp-new");
        assertEquals(2, fileMapper.renameDescendantStorePaths(oldPrefix, newPrefix),
                "只有以 oldPrefix + '/' 开头的两行会被改写");
        assertEquals(oldPrefix, fileMapper.getFileNodeById(base.getId()).getStorePath(),
                "oldPrefix 本身不匹配 LIKE 'oldPrefix/%'，必须保持原样");
        assertEquals(newPrefix + "/child", fileMapper.getFileNodeById(child.getId()).getStorePath());
        assertEquals(newPrefix + "/child/grand", fileMapper.getFileNodeById(grand.getId()).getStorePath());
    }

    /**
     * {@code batchRenameByIds}：一条 UPDATE 内用 {@code CASE id WHEN ... THEN ...} 批量改名，
     * 两个 {@code <foreach>} 分别供 SET 与 WHERE 使用（共用同一个 Map）。
     */
    @Test
    void batchRenameByIdsWithCaseWhen() {
        long userId = nextId();
        Folder root = personalRoot(userId);
        FileItem a = insertFileItem(buildFileItem(uniq("br-a"), root.getId(), userId, null, 1, null, 1L));
        FileItem b = insertFileItem(buildFileItem(uniq("br-b"), root.getId(), userId, null, 1, null, 1L));
        FileItem untouched = insertFileItem(buildFileItem(uniq("br-c"), root.getId(), userId, null, 1, null, 1L));

        String newA = uniq("br-a2");
        String newB = uniq("br-b2");
        Map<Long, String> renameMap = new LinkedHashMap<>();
        renameMap.put(a.getId(), newA);
        renameMap.put(b.getId(), newB);

        fileMapper.batchRenameByIds(renameMap);
        assertEquals(newA, fileMapper.getFileNodeById(a.getId()).getOriginalName());
        assertEquals(newB, fileMapper.getFileNodeById(b.getId()).getOriginalName());
        assertEquals(untouched.getOriginalName(), fileMapper.getFileNodeById(untouched.getId()).getOriginalName(),
                "不在 Map 里的 id 不应被 CASE ... END 改掉");
    }

    // ==========================================================================
    // 10. 删除 → 恢复 → 彻底删除 生命周期
    // ==========================================================================

    /** {@code logicalDeleteByIds} / {@code restoreByIds} / {@code reallyDeleteByIds}（三者都是 foreach 动态 UPDATE）。 */
    @Test
    void deleteRestoreAndPurgeLifecycle() {
        long userId = nextId();
        Folder root = personalRoot(userId);
        FileItem a = insertFileItem(buildFileItem(uniq("lc-a"), root.getId(), userId, null, 1, null, 1L));
        FileItem b = insertFileItem(buildFileItem(uniq("lc-b"), root.getId(), userId, null, 1, null, 1L));

        // 软删：deleted = 1 且记录 deleted_user_id
        assertEquals(2, fileMapper.logicalDeleteByIds(List.of(a.getId(), b.getId()), userId));
        FileNode deletedA = fileMapper.getFileNodeById(a.getId());
        assertEquals(1, deletedA.getDeleted());
        assertEquals(userId, deletedA.getDeletedUserId());

        // 恢复：deleted = 0
        assertEquals(2, fileMapper.restoreByIds(List.of(a.getId(), b.getId())));
        assertEquals(0, fileMapper.getFileNodeById(a.getId()).getDeleted());
        assertEquals(0, fileMapper.restoreByIds(List.of(a.getId())), "restoreByIds 只影响 deleted = 1 的行");

        // 彻底删除：deleted = 2
        assertEquals(1, fileMapper.reallyDeleteByIds(List.of(b.getId()), userId));
        FileNode purged = fileMapper.getFileNodeById(b.getId());
        assertEquals(2, purged.getDeleted());
        assertEquals(userId, purged.getDeletedUserId());

        // 已在回收站的行也能被彻底删除（WHERE deleted IN (0, 1)）
        fileMapper.logicalDeleteByIds(List.of(a.getId()), userId);
        assertEquals(1, fileMapper.reallyDeleteByIds(List.of(a.getId()), userId));
        assertEquals(2, fileMapper.getFileNodeById(a.getId()).getDeleted());
        assertEquals(0, fileMapper.reallyDeleteByIds(List.of(a.getId()), userId),
                "deleted 已是 2 的行不在 deleted IN (0, 1) 内，影响 0 行");
    }

    // ==========================================================================
    // 11. 存储用量聚合
    // ==========================================================================

    /**
     * {@code sumActiveFileSize}（个人 / 团队 / 项目三分支）、{@code sumPersonalStorageByUsers}、
     * {@code listPersonalStorageUsageByUsers}、{@code sumActiveFileSizeByTeamIds}。
     *
     * <p>共同口径：{@code deleted IN (0, 1)}（**回收站仍占额度**）、{@code file_type = 1}（文件夹不计，
     * 且文件夹的 file_size 为 NULL）。</p>
     */
    @Test
    void storageAggregationsRespectQuotaSemantics() {
        long userId = nextId();
        Folder root = personalRoot(userId);

        FileItem f1 = insertFileItem(buildFileItem(uniq("s-1"), root.getId(), userId, null, 1, null, 100L));
        insertFileItem(buildFileItem(uniq("s-2"), root.getId(), userId, null, 1, null, 250L));
        insertFolder(buildFolder(uniq("s-folder"), root.getId(), userId, null, 1, null));

        // 个人分支（teamId / spaceType / projectId 全 null）
        assertEquals(350L, fileMapper.sumActiveFileSize(userId, null, null, null),
                "文件夹 file_size 为 NULL 不得计入");
        // 软删后仍在额度内
        fileMapper.logicalDeleteByIds(List.of(f1.getId()), userId);
        assertEquals(350L, fileMapper.sumActiveFileSize(userId, null, null, null),
                "deleted = 1（回收站）仍占额度");
        // 彻底删除后释放
        fileMapper.reallyDeleteByIds(List.of(f1.getId()), userId);
        assertEquals(250L, fileMapper.sumActiveFileSize(userId, null, null, null),
                "deleted = 2 必须释放额度");
        // 别的用户不受影响
        assertEquals(0L, fileMapper.sumActiveFileSize(nextId(), null, null, null));

        // 团队分支：space_type = 2 AND team_id
        long teamId = nextId();
        Folder teamRoot = insertFolder(buildFolder(uniq("s-troot"), -1L, userId, teamId, 2, null));
        insertFileItem(buildFileItem(uniq("s-t1"), teamRoot.getId(), userId, teamId, 2, null, 1000L));
        insertFileItem(buildFileItem(uniq("s-t2"), teamRoot.getId(), userId, teamId, 2, null, 2000L));
        assertEquals(3000L, fileMapper.sumActiveFileSize(null, teamId, 2, null));

        // 项目分支：space_type = 3 AND project_id
        long projectId = nextId();
        Folder projectRoot = insertFolder(buildFolder(uniq("s-prroot"), -1L, userId, teamId, 3, projectId));
        insertFileItem(buildFileItem(uniq("s-pr1"), projectRoot.getId(), userId, teamId, 3, projectId, 5000L));
        assertEquals(5000L, fileMapper.sumActiveFileSize(null, teamId, 3, projectId));

        // sumPersonalStorageByUsers / listPersonalStorageUsageByUsers：多用户 IN + 归组
        long userX = nextId();
        long userY = nextId();
        Folder rootX = personalRoot(userX);
        Folder rootY = personalRoot(userY);
        insertFileItem(buildFileItem(uniq("s-x"), rootX.getId(), userX, null, 1, null, 7L));
        insertFileItem(buildFileItem(uniq("s-y1"), rootY.getId(), userY, null, 1, null, 11L));
        insertFileItem(buildFileItem(uniq("s-y2"), rootY.getId(), userY, null, 1, null, 13L));

        assertEquals(7L, fileMapper.sumPersonalStorageByUsers(List.of(userX)));
        assertEquals(31L, fileMapper.sumPersonalStorageByUsers(List.of(userX, userY)), "7 + (11 + 13)");
        assertEquals(0L, fileMapper.sumPersonalStorageByUsers(List.of(nextId())),
                "无数据的用户 COALESCE 后应为 0 而不是 null");

        Map<Long, Long> perUser = fileMapper.listPersonalStorageUsageByUsers(List.of(userX, userY)).stream()
                .collect(Collectors.toMap(PersonalStorageUsage::getUserId, PersonalStorageUsage::getUsedStorage));
        assertEquals(2, perUser.size(), "GROUP BY upload_user_id ⇒ 每人一行");
        assertEquals(7L, perUser.get(userX).longValue());
        assertEquals(24L, perUser.get(userY).longValue());

        // sumActiveFileSizeByTeamIds：space_type = 2 的按 team_id 归组
        List<TeamStorageUsage> teamUsage = fileMapper.sumActiveFileSizeByTeamIds(List.of(teamId));
        assertEquals(1, teamUsage.size());
        assertEquals(teamId, teamUsage.get(0).getTeamId());
        assertEquals(3000L, teamUsage.get(0).getUsedStorage(),
                "项目空间(space_type=3)的 5000 字节不得计入团队用量");
    }

    // ==========================================================================
    // 12. 文本块（@Select("""...""")）与物理清理
    // ==========================================================================

    /** {@code getPersonalRootFileIds}：注销清理链路依赖 parent_id = -1 哨兵（V5 迁移的口径）。 */
    @Test
    void getPersonalRootFileIdsFindsSentinelRoots() {
        long userId = nextId();
        Folder root = personalRoot(userId);
        FileItem underRoot = insertFileItem(buildFileItem(uniq("g-child"), root.getId(), userId, null, 1, null, 1L));
        FileItem rootLevelFile = insertFileItem(buildFileItem(uniq("g-rootfile"), -1L, userId, null, 1, null, 1L));

        List<Long> rootIds = fileMapper.getPersonalRootFileIds(userId);
        assertEquals(2, rootIds.size(), "parent_id = -1 的根目录 + 根级文件");
        assertTrue(rootIds.contains(root.getId()));
        assertTrue(rootIds.contains(rootLevelFile.getId()));
        assertFalse(rootIds.contains(underRoot.getId()), "子节点不是根");
        assertTrue(fileMapper.getPersonalRootFileIds(nextId()).isEmpty());

        // 软删后仍在（deleted IN (0, 1)）
        fileMapper.logicalDeleteByIds(List.of(root.getId()), userId);
        assertTrue(fileMapper.getPersonalRootFileIds(userId).contains(root.getId()),
                "deleted IN (0, 1) ⇒ 回收站里的根目录也要返回（否则注销清理会漏）");

        // 团队空间的根不算个人根
        long teamId = nextId();
        Folder teamRoot = insertFolder(buildFolder(uniq("g-troot"), -1L, userId, teamId, 2, null));
        assertFalse(fileMapper.getPersonalRootFileIds(userId).contains(teamRoot.getId()));
    }

    /**
     * {@code selectRecycleExpiredRootIds} / {@code selectTombstoneExpiredIds} / {@code deleteTombstoneRows}。
     *
     * <p>⚠️ 前两条是**全局扫描**（无 user / space 谓词，只有 {@code modify_time < cutoff} + {@code LIMIT}），
     * 因此只能断言「本用例的行被包含在内 / 不被包含在内」，不能断言总数。
     * 传大 limit 以降低被其它用例的行挤出窗口的概率。</p>
     */
    @Test
    void expiredScanAndTombstonePurge() {
        long userId = nextId();
        Folder root = personalRoot(userId);
        Folder sub = insertFolder(buildFolder(uniq("e-sub"), root.getId(), userId, null, 1, null));
        FileItem recycleRoot = insertFileItem(buildFileItem(uniq("e-recycle"), sub.getId(), userId, null, 1, null, 9L));
        FileItem tombstone = insertFileItem(buildFileItem(uniq("e-tomb"), sub.getId(), userId, null, 1, null, 9L));

        // 先都进回收站，再把其中一个升级为墓碑（deleted = 2）
        fileMapper.logicalDeleteByIds(List.of(recycleRoot.getId(), tombstone.getId()), userId);
        fileMapper.reallyDeleteByIds(List.of(tombstone.getId()), userId);

        // cutoff 取未来 ⇒ modify_time < cutoff 恒真；limit 取大以避开其它用例的行
        Timestamp future = Timestamp.valueOf(LocalDateTime.now().plusDays(1));
        List<Long> expiredRoots = fileMapper.selectRecycleExpiredRootIds(future, 100_000);
        assertTrue(expiredRoots.contains(recycleRoot.getId()), "回收站根必须被扫描到");
        assertFalse(expiredRoots.contains(tombstone.getId()), "deleted = 2 的行不是回收站根");

        // cutoff 取过去 ⇒ modify_time < cutoff 恒假
        Timestamp past = Timestamp.valueOf(LocalDateTime.now().minusYears(10));
        assertFalse(fileMapper.selectRecycleExpiredRootIds(past, 100_000).contains(recycleRoot.getId()),
                "modify_time < cutoff 谓词丢了立刻变红");
        assertFalse(fileMapper.selectTombstoneExpiredIds(past, 100_000).contains(tombstone.getId()));

        // 祖先也被软删 ⇒ 不再是"回收站根"
        fileMapper.logicalDeleteByIds(List.of(sub.getId()), userId);
        assertFalse(fileMapper.selectRecycleExpiredRootIds(future, 100_000).contains(recycleRoot.getId()),
                "NOT EXISTS 祖先谓词：祖先已删的节点不是回收站根");

        // 墓碑扫描：deleted = 2 且过期
        List<Long> expiredTombstones = fileMapper.selectTombstoneExpiredIds(future, 100_000);
        assertTrue(expiredTombstones.contains(tombstone.getId()), "deleted = 2 且过期的墓碑必须被扫描到");
        assertFalse(expiredTombstones.contains(recycleRoot.getId()), "deleted = 1 不是墓碑");

        // deleteTombstoneRows：物理删除（真 DELETE），只影响传入 id
        assertEquals(1, fileMapper.deleteTombstoneRows(List.of(tombstone.getId())));
        assertNull(fileMapper.getFileNodeById(tombstone.getId()), "墓碑行必须被物理删除");
        assertNotNull(fileMapper.getFileNodeById(recycleRoot.getId()), "未传入的行不受影响");
        assertEquals(0, fileMapper.deleteTombstoneRows(List.of(tombstone.getId())), "重复删影响 0 行");
    }

    /** {@code sumDeletedFileBytesByScopeKey}（文本块 + 内嵌 {@code <script>}）与 {@code selectScopeUsageAll}。 */
    @Test
    void scopeKeyAggregations() {
        long userId = nextId();
        Folder root = personalRoot(userId);
        FileItem f1 = insertFileItem(buildFileItem(uniq("sk-1"), root.getId(), userId, null, 1, null, 128L));
        FileItem f2 = insertFileItem(buildFileItem(uniq("sk-2"), root.getId(), userId, null, 1, null, 256L));
        insertFolder(buildFolder(uniq("sk-folder"), root.getId(), userId, null, 1, null));

        // V6 生成列口径：个人空间 scope_key = CONCAT('U', upload_user_id)
        String personalScope = "U" + userId;

        // 按传入 id 聚合；不含 deleted 谓词；file_size 为 NULL 的行（文件夹）被排除
        List<Map<String, Object>> rows = fileMapper.sumDeletedFileBytesByScopeKey(List.of(f1.getId(), f2.getId()));
        assertEquals(1, rows.size(), "两行同属一个 scope_key ⇒ GROUP BY 后一行");
        assertEquals(personalScope, rows.get(0).get("scopeKey"), "别名 scopeKey 必须可读");
        assertEquals(384L, ((Number) rows.get(0).get("totalBytes")).longValue());
        assertEquals(List.of(), fileMapper.sumDeletedFileBytesByScopeKey(List.of(nextId())), "无命中应返回空列表");

        // selectScopeUsageAll：全局按 scope_key 聚合，只断言本用例的键存在且数值正确
        Map<String, Long> all = fileMapper.selectScopeUsageAll().stream()
                .collect(Collectors.toMap(m -> (String) m.get("scopeKey"),
                        m -> ((Number) m.get("totalBytes")).longValue()));
        assertTrue(all.containsKey(personalScope), "全局聚合里必须包含本用例的个人空间键 " + personalScope);
        assertEquals(384L, all.get(personalScope).longValue());

        // 彻底删除后（deleted = 2）不计入存活口径
        fileMapper.reallyDeleteByIds(List.of(f1.getId()), userId);
        Map<String, Long> afterPurge = fileMapper.selectScopeUsageAll().stream()
                .collect(Collectors.toMap(m -> (String) m.get("scopeKey"),
                        m -> ((Number) m.get("totalBytes")).longValue()));
        assertEquals(256L, afterPurge.get(personalScope).longValue(),
                "deleted IN (0, 1) ⇒ deleted=2 的行必须出账");
    }
}
