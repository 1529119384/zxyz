package uno.acloud.file.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FileMapper} 的「装配门禁」——**不连数据库、不起 Spring 容器**，
 * 只把 MyBatis 的配置按启动时的顺序装配一遍，验证注解 SQL 与 mapper XML 能否共存。
 *
 * <h2>为什么需要它</h2>
 * <p>把内联 SQL 迁到 XML（{@code mapper/FileMapper.xml}，P2-7）时，事故基本只有三类，
 * 且都发生在「装配期」而不是「执行期」：</p>
 * <ol>
 *   <li><b>同 statement id 双定义</b>（注解没删净 + XML 新增）⇒ 启动即抛
 *       {@code Mapped Statements collection already contains value for …}，整个服务起不来；</li>
 *   <li><b>namespace 写错</b> ⇒ XML 能加载但语句挂不到接口上，调用时报
 *       {@code BindingException: Invalid bound statement (not found)}；</li>
 *   <li><b>XML 根本没被扫到</b>（放在错误的目录下）⇒ 与 2 同症状。</li>
 * </ol>
 * <p>这三类都是「CI 才能发现」的重活，而它们其实**不需要真实数据库**就能判定。
 * 本类用 {@link MybatisConfiguration#addMapper(Class)} 复刻启动时的装配顺序
 * （XML 先解析、注解后解析），把「本机可跑」的判定权收回来，
 * 避免每移一条 SQL 都要烧一轮 CI。</p>
 *
 * <p>另有第四个不变量：{@link FileNode} 是<b>抽象类</b>，其子类 {@code FileItem} / {@code Folder}
 * 只能靠 {@code fileNodeResultMap} 的 {@code <discriminator>} 落地。缺判别器时
 * MyBatis 会拿抽象类去实例化并抛 {@code InstantiationException} —— 也就是「查得到行」反而更糟。
 * 这一条同样不需要数据库，本类一并钉住。</p>
 *
 * <h2>它不能替代什么</h2>
 * <p>SQL 的<b>语义</b>（WHERE 条件、排序、分页、唯一键行为）必须由
 * {@link FileMapperIntegrationTest} 在真实 MySQL 上验证。本类只保证「语句都被正确装配了」。</p>
 */
class FileMapperWiringTest {

    private static final String NS = "uno.acloud.file.infrastructure.mapper.FileMapper";

    /**
     * 41 = {@code @Select} 29 + {@code @Update} 9 + {@code @Insert} 2 + {@code @Delete} 1。
     * <p>迁移只搬「定义写在哪」，语句总数不变 ⇒ 这个数字在整轮 P2-7 期间恒为 41。
     * 它同时兜住「迁移时漏搬 / 误删一条」。</p>
     */
    private static final int EXPECTED_STATEMENTS = 41;

    /**
     * MyBatis-Plus 默认的 {@code mapper-locations}（已核 starter 配置元数据，非推断：
     * {@code mybatis-plus.mapper-locations} 默认值即 {@code classpath*:/mapper/**\/*.xml}）。
     * <p>⚠️ 注意属性前缀是 {@code mybatis-plus.*}；仓库里 im-service 写的 {@code mybatis.mapper-locations}
     * 属于<b>前缀写错、静默无效</b>，它之所以能用，是靠这个默认值兜底。</p>
     */
    private static final String XML_RESOURCE = "mapper/FileMapper.xml";

    /**
     * 复刻启动期装配顺序，两步缺一不可：
     * <ol>
     *   <li><b>mapper-locations 扫描</b> —— 把 {@code mapper/FileMapper.xml} 读进来并解析。
     *       这一步在真实应用里由 MyBatis-Plus 完成；
     *       {@code XMLMapperBuilder.parse()} 内部还会调 {@code bindMapperForNamespace()}，
     *       顺带把 namespace 指向的接口注册了（连带解析其注解）。</li>
     *   <li><b>注册接口</b> —— 复刻 {@code MapperScannerConfigurer} / {@code MapperFactoryBean.checkDaoConfig()}
     *       的守卫：若第 1 步已注册过就跳过。</li>
     * </ol>
     * <p>⚠️ 只调 {@code addMapper()} 是<b>不够</b>的：那条路径只会去接口的<b>同包路径</b>
     * （{@code uno/acloud/file/infrastructure/mapper/FileMapper.xml}）找 XML，
     * 找不到 {@code mapper/} 下的文件 —— 本类初版就因此误判「XML 没被扫到」。
     * 这类假阴性正是「只测装配、不测真实加载路径」的代价，故此处显式两步复刻。</p>
     */
    private static Configuration assemble() {
        Configuration configuration = new MybatisConfiguration();

        try (InputStream xml = Resources.getResourceAsStream(XML_RESOURCE)) {
            assertNotNull(xml, XML_RESOURCE + " 不在 classpath 上 ⇒ 它必须位于 "
                    + "src/main/resources/mapper/ 下，且属性前缀是 mybatis-plus.*（默认值才包含该目录）");
            new XMLMapperBuilder(xml, configuration, XML_RESOURCE, configuration.getSqlFragments()).parse();
        } catch (IOException e) {
            throw new UncheckedIOException("加载 " + XML_RESOURCE + " 失败", e);
        }

        if (!configuration.hasMapper(FileMapper.class)) {
            configuration.addMapper(FileMapper.class);
        }
        return configuration;
    }

    private static Set<String> statementIds(Configuration configuration) {
        Set<String> ids = new LinkedHashSet<>();
        for (MappedStatement statement : configuration.getMappedStatements()) {
            ids.add(statement.getId());
        }
        return ids;
    }

    private static MappedStatement require(Configuration configuration, String id) {
        MappedStatement statement = configuration.getMappedStatement(NS + "." + id);
        assertNotNull(statement, "语句未注册：" + id
                + "（多半是注解已删但 XML 没被扫到 —— 检查 namespace 与 mapper 目录）");
        return statement;
    }

    // ==========================================================================
    // 一、总量与唯一性
    // ==========================================================================

    @Test
    void everyStatementIsRegisteredExactlyOnce() {
        Configuration configuration = assemble();

        Set<String> ids = statementIds(configuration);
        // 同 id 双定义会在 assemble() 里直接抛，能走到这里就说明「没有重复」。
        assertEquals(EXPECTED_STATEMENTS, ids.size(),
                "语句总数应为 " + EXPECTED_STATEMENTS + " 条（注解 + XML 合计），实际 " + ids.size()
                        + " 条 ⇒ 有语句在迁移中被漏搬或误删");

        for (String id : ids) {
            assertTrue(id.startsWith(NS + "."), "出现了不属于本 mapper 的语句：" + id);
        }
    }

    // ==========================================================================
    // 二、已迁到 XML 的语句：必须由 XML 承载
    // ==========================================================================

    /**
     * 这批是 P2-7 批次 1 迁入 {@code mapper/FileMapper.xml} 的 13 条（8 查询 + 5 更新）。
     * <p>断言 {@link MappedStatement#getResource()}：XML 定义的语句其值就是 XML 资源路径；
     * 而注解定义的语句，MyBatis 会填 {@code "…FileMapper.java (best guess)"}
     * （见 {@code MapperAnnotationBuilder} 构造里的 resource）。因此这个字段能**直接**区分
     * 「由 XML 承载」与「还留在注解里」，是「XML 真的被扫到并被采用」的证据，
     * 而不是靠「跑起来没报错」倒推。</p>
     */
    @Test
    void migratedStatementsAreBackedByXml() {
        Configuration configuration = assemble();

        assertBackedByXml(configuration, "countActiveChildren", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "getParentId", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "countByParentId", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "countByKeyword", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "getActiveNamesByParentIdAndFileType", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "getPersonalRootFileIds", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "getOssKeysByIds", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "collectDescendantIds", SqlCommandType.SELECT);

        assertBackedByXml(configuration, "renameNodeById", SqlCommandType.UPDATE);
        assertBackedByXml(configuration, "moveNodeById", SqlCommandType.UPDATE);
        assertBackedByXml(configuration, "updateStorePathById", SqlCommandType.UPDATE);
        assertBackedByXml(configuration, "updateStorePathAndSpaceById", SqlCommandType.UPDATE);
        assertBackedByXml(configuration, "renameDescendantStorePaths", SqlCommandType.UPDATE);
    }

    /**
     * 批次 2 迁入 {@code mapper/FileMapper.xml} 的 11 条：
     * 动态 UPDATE/DELETE（{@code <foreach>} / {@code CASE id WHEN}）、存储聚合、过期扫描。
     * <p>这一批踩的是「{@code <script>} 包裹层忘删」与「{@code <} 未转义」两个坑 ——
     * 两者都会让 XML 解析直接失败，本用例因此是它们的第一道拦截。</p>
     */
    @Test
    void batchTwoStatementsAreBackedByXml() {
        Configuration configuration = assemble();

        assertBackedByXml(configuration, "batchRenameByIds", SqlCommandType.UPDATE);
        assertBackedByXml(configuration, "logicalDeleteByIds", SqlCommandType.UPDATE);
        assertBackedByXml(configuration, "restoreByIds", SqlCommandType.UPDATE);
        assertBackedByXml(configuration, "reallyDeleteByIds", SqlCommandType.UPDATE);
        assertBackedByXml(configuration, "deleteTombstoneRows", SqlCommandType.DELETE);

        assertBackedByXml(configuration, "sumActiveFileSize", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "sumPersonalStorageByUsers", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "sumDeletedFileBytesByScopeKey", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "selectScopeUsageAll", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "selectRecycleExpiredRootIds", SqlCommandType.SELECT);
        assertBackedByXml(configuration, "selectTombstoneExpiredIds", SqlCommandType.SELECT);
    }

    /**
     * 迁移收尾形态：**全部 41 条语句都由 {@code mapper/FileMapper.xml} 承载**，
     * 接口里不再残留任何内联 SQL。
     *
     * <p>这是整轮 P2-7 的终态断言 —— 任何「漏搬一条」都会在这里被点名，
     * 而不必等运行时才抛 {@code BindingException: Invalid bound statement (not found)}。</p>
     */
    @Test
    void noStatementIsStillInlineAnnotated() {
        Configuration configuration = assemble();

        List<String> stillInline = new ArrayList<>();
        for (MappedStatement statement : configuration.getMappedStatements()) {
            if (!statement.getId().startsWith(NS + ".")) {
                continue;
            }
            String resource = statement.getResource();
            if (resource == null || !resource.contains("FileMapper.xml")) {
                stillInline.add(statement.getId() + " <- " + resource);
            }
        }
        assertTrue(stillInline.isEmpty(), "仍有语句留在接口注解里、未迁到 XML：" + stillInline);
    }

    private static void assertBackedByXml(Configuration configuration, String id, SqlCommandType expected) {
        MappedStatement statement = require(configuration, id);
        assertEquals(expected, statement.getSqlCommandType(), id + " 的语句类型不符");
        String resource = statement.getResource();
        assertTrue(resource != null && resource.contains("FileMapper.xml"),
                id + " 并未由 mapper/FileMapper.xml 承载（resource=" + resource
                        + "）⇒ XML 可能放在未被扫到的目录，或该条仍留在注解里");
        assertFalse(statement.getSqlSource() == null, id + " 未解析出 SqlSource");
    }

    // ==========================================================================
    // 三、抽象类判别器不变量
    // ==========================================================================

    /**
     * 凡返回 {@link FileNode} / {@code List<FileNode>} 的语句，结果映射必须携带判别器。
     * <p>2026-09-20 实测过缺失时的形态：{@code discriminator=null}、{@code resultMappings=0}，
     * 且「查到行」会抛 {@code ReflectionException: … FileNode … InstantiationException}。</p>
     */
    @Test
    void everyFileNodeReturningStatementKeepsItsDiscriminator() {
        Configuration configuration = assemble();

        int checked = 0;
        for (Method method : FileMapper.class.getDeclaredMethods()) {
            if (method.isDefault() || method.isSynthetic() || method.isBridge()) {
                continue;
            }
            if (!returnsFileNode(method)) {
                continue;
            }
            MappedStatement statement = require(configuration, method.getName());
            assertFalse(statement.getResultMaps().isEmpty(), method.getName() + " 没有结果映射");
            assertNotNull(statement.getResultMaps().get(0).getDiscriminator(),
                    method.getName() + " 丢失了 file_type 判别器 ⇒ FileNode 是抽象类，"
                            + "查到行时会抛 InstantiationException（补 @ResultMap(\"fileNodeResultMap\")）");
            checked++;
        }

        // 1 条承载 resultMap（getFileNodeById）+ 10 条引用它。
        assertTrue(checked >= 11, "扫描到的 FileNode 返回型语句只有 " + checked
                + " 条，少于预期的 11 条 ⇒ 反射扫描本身可能失效");
    }

    private static boolean returnsFileNode(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == FileNode.class) {
            return true;
        }
        if (!List.class.isAssignableFrom(returnType)) {
            return false;
        }
        Type generic = method.getGenericReturnType();
        if (!(generic instanceof ParameterizedType parameterized)) {
            return false;
        }
        return parameterized.getActualTypeArguments()[0] == FileNode.class;
    }

    /**
     * {@link ResultMap#getMappedColumns()} 返回的是<b>大写</b>列名
     * （MyBatis 在 ResultMap 构造里统一 {@code toUpperCase}）⇒ 比对前必须归一，
     * 否则会得到「明明映射了却报缺列」的假红。
     */
    private static Set<String> mappedColumnsUpper(ResultMap resultMap) {
        Set<String> upper = new LinkedHashSet<>();
        for (String column : resultMap.getMappedColumns()) {
            upper.add(column.toUpperCase(Locale.ROOT));
        }
        return upper;
    }

    /**
     * 批次 3：{@code fileNodeResultMap} 本体（含判别器）迁入 XML，形状必须逐项不变。
     *
     * <p>这是整轮迁移里**唯一无法用「SQL 文本等价」来证明**的一步 —— SQL 一个字没改，
     * 但 {@code @Results} + {@code @TypeDiscriminator} 换成了 {@code <resultMap>} + {@code <discriminator>}。
     * 故此处把形状钉死：顶层 15 条映射、判别列 {@code file_type}、两个分支分别落
     * {@link FileItem} / {@link Folder}，且 {@code file_type = 1} 那一支的 4 条追加列不能丢。</p>
     */
    @Test
    void fileNodeResultMapKeepsItsShapeAfterMovingToXml() {
        Configuration configuration = assemble();

        ResultMap resultMap = configuration.getResultMap(NS + ".fileNodeResultMap");
        assertNotNull(resultMap, "fileNodeResultMap 未注册 ⇒ XML 没被扫到，或 id / namespace 写错");
        assertEquals(FileNode.class, resultMap.getType());
        // 迁移前（注解 @Results）实测为 15 条顶层映射：1 个 <id> + 14 个 <result>。
        assertEquals(15, resultMap.getResultMappings().size(), "顶层结果映射条数变了");

        Discriminator discriminator = resultMap.getDiscriminator();
        assertNotNull(discriminator, "判别器丢了 ⇒ 抽象类 FileNode 无法实例化，查到行即 500");
        assertEquals("file_type", discriminator.getResultMapping().getColumn());
        assertEquals(2, discriminator.getDiscriminatorMap().size(),
                "判别分支应为 file_type=1 → FileItem、file_type=0 → Folder");

        // Discriminator 只暴露「case 值 → 结果映射 id」；case 的类型要回 Configuration 里查
        // （XML 的每个 <case> 会各自生成一个嵌套 resultMap）。
        String fileItemCaseMapId = discriminator.getMapIdFor("1");
        String folderCaseMapId = discriminator.getMapIdFor("0");
        assertNotNull(fileItemCaseMapId, "缺少 file_type=1 分支");
        assertNotNull(folderCaseMapId, "缺少 file_type=0 分支");

        ResultMap fileItemCase = configuration.getResultMap(fileItemCaseMapId);
        assertEquals(FileItem.class, fileItemCase.getType());
        assertEquals(Folder.class, configuration.getResultMap(folderCaseMapId).getType());

        // ⚠️ 判别分支的嵌套 resultMap 会**整份替换**父 resultMap 参与映射
        //    （见 DefaultResultSetHandler#getDiscriminatedResultMap），
        //    因此它必须自带父的全部列，再加本分支的追加列 —— 少一条就会静默丢字段。
        //    （实测：file_type=1 分支的 resultMappings 为 15 + 4 = 19 条。）
        Set<String> expectedForFileItem = mappedColumnsUpper(resultMap);
        for (String extraColumn : List.of("uuid_name", "category", "file_size", "file_url")) {
            expectedForFileItem.add(extraColumn.toUpperCase(Locale.ROOT));
        }
        expectedForFileItem.removeAll(mappedColumnsUpper(fileItemCase));
        assertTrue(expectedForFileItem.isEmpty(),
                "file_type=1 分支缺列（会静默丢字段）：" + expectedForFileItem);

        Set<String> missingForFolder = mappedColumnsUpper(resultMap);
        missingForFolder.removeAll(mappedColumnsUpper(configuration.getResultMap(folderCaseMapId)));
        assertTrue(missingForFolder.isEmpty(),
                "file_type=0 分支缺列（会静默丢字段）：" + missingForFolder);
    }

    /**
     * 别名列结果映射：{@code <result column>} 必须写 SQL 里的<b>别名</b>（{@code teamId}），
     * 写成原列名（{@code team_id}）不会报错，只会让字段**静默为 null**。
     * <p>这条属于「SQL 文本等价」覆盖不到的风险（SQL 一模一样，错的是映射声明），
     * 故在此单独钉住。</p>
     */
    @Test
    void aliasedResultMapsUseAliasNotRawColumnName() {
        Configuration configuration = assemble();

        ResultMap search = configuration.getResultMap(NS + ".fileSearchItemResultMap");
        assertNotNull(search, "fileSearchItemResultMap 未注册");
        assertTrue(mappedColumnsUpper(search).contains("TEAMID"),
                "searchByKeyword 的团队列必须映射别名 teamId（SQL 里是 team_id AS teamId）");

        ResultMap personal = configuration.getResultMap(NS + ".personalStorageUsageResultMap");
        assertNotNull(personal, "personalStorageUsageResultMap 未注册");
        assertTrue(mappedColumnsUpper(personal).contains("USERID"), "应映射别名 userId");
        assertTrue(mappedColumnsUpper(personal).contains("USEDSTORAGE"), "应映射别名 usedStorage");

        ResultMap team = configuration.getResultMap(NS + ".teamStorageUsageResultMap");
        assertNotNull(team, "teamStorageUsageResultMap 未注册");
        assertTrue(mappedColumnsUpper(team).contains("TEAMID"), "应映射别名 teamId");
        assertTrue(mappedColumnsUpper(team).contains("USEDSTORAGE"), "应映射别名 usedStorage");
    }

    // ==========================================================================
    // 四、两条孪生语句的 WHERE 必须同形（迁移时最容易只改一处）
    // ==========================================================================

    /**
     * {@code getFileNodesInRecycleBinPaged} 与 {@code countFileNodesInRecycleBin} 的源码注释
     * 明确要求「WHERE 条件逐字一致」，否则分页器总条数与翻页结果会静默错位。
     * <p>此处只做**同一载体内**的结构性对账：两者必须都由 XML 承载（或都由注解承载），
     * 不允许一个在 XML、另一个落在注解里 —— 那种半迁移状态最容易在后续批次中被漏改。</p>
     */
    @Test
    void recycleBinTwinsLiveInTheSameCarrier() {
        Configuration configuration = assemble();

        MappedStatement paged = require(configuration, "getFileNodesInRecycleBinPaged");
        MappedStatement count = require(configuration, "countFileNodesInRecycleBin");

        assertNotNull(paged.getResource(), "getFileNodesInRecycleBinPaged 的载体缺失");
        assertEquals(paged.getResource(), count.getResource(),
                "回收站分页与其计数语句必须落在同一个载体（都迁 XML，或都留在注解）；"
                        + "否则孪生 WHERE 迟早不同步");
    }

    /**
     * 钉住「下划线→驼峰」的<b>真实</b>生效值。
     *
     * <p>2026-09-20 实测：本仓依赖树里只有 {@code com.baomidou:mybatis-plus-spring-boot-autoconfigure}，
     * <b>没有</b> {@code org.mybatis.spring.boot:mybatis-spring-boot-autoconfigure} ——
     * 也就是没有任何组件绑定 {@code mybatis.*} 前缀。因此仓库里这些配置全是<b>静默无效</b>的：
     * {@code application-common.yml} 的 {@code mybatis.configuration.map-underscore-to-camel-case}、
     * {@code …/application-test.yml} 的 {@code mybatis.configuration.log-impl}、
     * im-service 的 {@code mybatis.mapper-locations}（它能用纯靠 MyBatis-Plus 默认值兜底）。</p>
     *
     * <p>⇒ 这个值完全来自 {@code MybatisConfiguration} 自身的默认。
     * 它一旦变化，所有依赖自动映射（而非显式 {@code @Result}/{@code <resultMap>}）的语句会静默错字段，
     * 故在此钉住。若此用例变红：先确认真实值，再决定是「补显式映射」还是「把配置前缀改对」。</p>
     */
    @Test
    void underscoreToCamelMappingDefaultIsPinned() {
        assertTrue(assemble().isMapUnderscoreToCamelCase(),
                "MybatisConfiguration 默认的 mapUnderscoreToCamelCase 变了 ⇒ 依赖自动映射的列会静默映射不上；"
                        + "仓库里那条 mybatis.configuration.* 是无效配置，改它没用，需用 mybatis-plus.configuration.*");
    }
}
