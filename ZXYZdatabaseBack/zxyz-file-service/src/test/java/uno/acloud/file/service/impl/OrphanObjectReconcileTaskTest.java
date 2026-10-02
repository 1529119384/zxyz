package uno.acloud.file.service.impl;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.ListObjectsRequest;
import com.aliyun.sdk.service.oss2.models.ListObjectsResult;
import com.aliyun.sdk.service.oss2.models.ObjectSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.FileObjectDeleteStatus;
import uno.acloud.common.oss.OSSProperties;
import uno.acloud.file.config.ServiceProperties;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F11（P3 可扩展性）：孤儿对象对账不得把全部 object_key 载入内存。
 *
 * <h2>修复前的缺陷</h2>
 * <p>{@code reconcileOrphanObjects} 先执行 {@code selectObjectKeysByPrefix("files/")}
 * 把平台<b>全部</b> object_key 装进一个 {@code Set}，再与 OSS 列表比对：</p>
 * <ul>
 *   <li>内存占用随对象数<b>线性增长</b>，百万级时每日任务有 OOM 风险；</li>
 *   <li>那条 {@code WHERE object_key LIKE 'files/%'} 本身就是一次<b>全表扫描</b>，
 *       且返回行数等于平台对象总数。</li>
 * </ul>
 *
 * <h2>修复后的语义</h2>
 * <p>改为<b>按 ListObjects 每页</b>（≤1000 条）批量查存在性
 * （{@code object_key IN (...)} 走唯一索引等值查找）。内存与 DB 返回量都被限制在单页量级，
 * 与平台总量解耦；孤儿判定结果<b>与修复前完全一致</b>。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrphanObjectReconcileTaskTest {

    @Mock
    private OSSClient ossClient;
    @Mock
    private FileObjectRefMapper fileObjectRefMapper;
    @Mock
    private StorageProviderRegistry registry;

    private OSSProperties ossProperties;
    private ServiceProperties serviceProperties;

    @BeforeEach
    void setUp() {
        ossProperties = new OSSProperties();
        ossProperties.setBucket("test-bucket");
        serviceProperties = new ServiceProperties();
        serviceProperties.getFileObjectDelete().setEnabled(true);
    }

    private OrphanObjectReconcileTask task() {
        return new OrphanObjectReconcileTask(ossClient, ossProperties, fileObjectRefMapper,
                registry, serviceProperties);
    }

    /** 默认提供者必须支持预签名直传，否则整个对账会被跳过。 */
    private void givenPresignedProvider() {
        StorageProvider provider = mock(StorageProvider.class);
        lenient().when(provider.supportsPresignedUpload()).thenReturn(true);
        lenient().when(provider.providerId()).thenReturn("oss");
        lenient().when(registry.getDefaultProvider()).thenReturn(provider);
    }

    private ObjectSummary summary(String key, Instant lastModified) {
        return ObjectSummary.newBuilder()
                .key(key)
                .lastModified(lastModified)
                .build();
    }

    /**
     * {@code ListObjectsResult} 的 Builder 没有公开的 contents/isTruncated setter
     * （SDK 只暴露 delegate 字段与只读访问器），因此这里直接 mock 结果对象。
     *
     * <p>⚠️ 调用方必须<b>先</b>用本方法建好结果对象、<b>再</b>把它传给
     * {@code when(ossClient.listObjects(...)).thenReturn(result)}。若在 {@code thenReturn(...)}
     * 的实参里现场调用本方法，Mockito 会认为前一个 stubbing 尚未完成并报
     * {@code UnfinishedStubbing}（bogus 的 "unfinished stubbing" 报错）。</p>
     */
    private ListObjectsResult page(List<ObjectSummary> contents, boolean truncated, String nextMarker) {
        ListObjectsResult result = mock(ListObjectsResult.class);
        lenient().when(result.contents()).thenReturn(contents);
        lenient().when(result.isTruncated()).thenReturn(truncated);
        lenient().when(result.nextMarker()).thenReturn(nextMarker);
        return result;
    }

    /**
     * 核心断言：<b>不得</b>调用「取全部已知键」的方法；存在性查询必须按页批量进行。
     *
     * <p>这条直接钉住 F11 的整改：只要有人把 {@code selectObjectKeysByPrefix} 调回来
     * （把全量 key 载入内存），本用例即变红。</p>
     */
    @Test
    void reconcile_queriesExistingKeysPerPageInsteadOfPreloadingAllKeys() {
        givenPresignedProvider();
        Instant old = Instant.now().minus(java.time.Duration.ofHours(48));

        ObjectSummary known = summary("files/known-1", old);
        ObjectSummary orphan = summary("files/orphan-1", old);
        ListObjectsResult singlePage = page(List.of(known, orphan), false, null);
        when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(singlePage);
        // 只有 known-1 已登记 ⇒ orphan-1 是孤儿
        when(fileObjectRefMapper.selectExistingObjectKeys(anyList()))
                .thenReturn(List.of("files/known-1"));
        when(fileObjectRefMapper.markOrphanPendingDelete(eq("files/orphan-1"), anyString(), eq("oss")))
                .thenReturn(1);

        task().reconcileOrphanObjects();

        // F11 关键：绝不再全量预载已知键
        verify(fileObjectRefMapper, never()).selectObjectKeysByPrefix(anyString());
        // 改为按页批量查存在性（本页两个候选键一次查完）
        verify(fileObjectRefMapper).selectExistingObjectKeys(List.of("files/known-1", "files/orphan-1"));
        // 孤儿仍被正确登记（判定结果与修复前一致）
        verify(fileObjectRefMapper).markOrphanPendingDelete(
                "files/orphan-1", FileObjectDeleteStatus.PENDING_DELETE, "oss");
    }

    /** 未满 24 小时的对象不得登记为孤儿（可能是在途/未确认上传）—— 既有安全底线不得回退。 */
    @Test
    void reconcile_skipsObjectsYoungerThanMinimumAge() {
        givenPresignedProvider();
        Instant fresh = Instant.now().minus(java.time.Duration.ofHours(1));

        ListObjectsResult singlePage = page(List.of(summary("files/in-flight", fresh)), false, null);
        when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(singlePage);

        task().reconcileOrphanObjects();

        // 年龄不足 ⇒ 连存在性查询都不必发（省一次 DB 往返）
        verify(fileObjectRefMapper, never()).selectExistingObjectKeys(anyList());
        verify(fileObjectRefMapper, never()).markOrphanPendingDelete(anyString(), anyString(), anyString());
    }

    /** 缺少 Last-Modified 的对象不得被清理（无法判断存活时长 ⇒ 保守跳过）。 */
    @Test
    void reconcile_skipsObjectsWithoutLastModified() {
        givenPresignedProvider();

        ListObjectsResult singlePage = page(List.of(summary("files/no-modified", null)), false, null);
        when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(singlePage);

        task().reconcileOrphanObjects();

        verify(fileObjectRefMapper, never()).markOrphanPendingDelete(anyString(), anyString(), anyString());
    }

    /**
     * 多页扫描：每页各自查存在性，跨页的孤儿都被登记。
     * <p>这条同时验证「分页游标推进」与「按页批量查询」配合正确 —— 若把存在性查询写成
     * 「只查第一页」，第二页的已登记对象就会被误判成孤儿并<b>被删除</b>（数据丢失方向）。</p>
     */
    @Test
    void reconcile_handlesMultiplePagesWithoutMisjudgingKnownObjects() {
        givenPresignedProvider();
        Instant old = Instant.now().minus(java.time.Duration.ofHours(48));

        ObjectSummary page1Known = summary("files/p1-known", old);
        ObjectSummary page2Known = summary("files/p2-known", old);
        ListObjectsResult first = page(List.of(page1Known), true, "marker-1");
        ListObjectsResult second = page(List.of(page2Known), false, null);
        when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(first, second);
        // 两页的键都「已登记」⇒ 都不该成为孤儿
        when(fileObjectRefMapper.selectExistingObjectKeys(anyList()))
                .thenAnswer(invocation -> new ArrayList<>((List<String>) invocation.getArgument(0)));

        task().reconcileOrphanObjects();

        // 第二页也必须被查（否则 p2-known 会被误判为孤儿并删除）
        verify(fileObjectRefMapper).selectExistingObjectKeys(List.of("files/p2-known"));
        verify(fileObjectRefMapper, never()).markOrphanPendingDelete(anyString(), anyString(), anyString());
    }

    /** 一个对象都没有时不得发存在性查询（省一次无意义的 DB 往返）。 */
    @Test
    void reconcile_skipsExistenceQueryWhenNoCandidates() {
        givenPresignedProvider();
        ListObjectsResult emptyPage = page(List.of(), false, null);
        when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(emptyPage);

        task().reconcileOrphanObjects();

        verify(fileObjectRefMapper, never()).selectExistingObjectKeys(anyList());
    }

    /** 物理删除被禁用时必须整体跳过（既有开关语义，不得回退）。 */
    @Test
    void reconcile_skipsEntirelyWhenDeletionDisabled() {
        serviceProperties.getFileObjectDelete().setEnabled(false);

        task().reconcileOrphanObjects();

        verifyNoInteractions(ossClient);
        verify(fileObjectRefMapper, never()).selectExistingObjectKeys(anyList());
    }

    /** bucket 未配置时跳过（否则 ListObjects 必然失败）。 */
    @Test
    void reconcile_skipsWhenBucketNotConfigured() {
        givenPresignedProvider();
        ossProperties.setBucket("  ");

        task().reconcileOrphanObjects();

        verifyNoInteractions(ossClient);
    }
}
