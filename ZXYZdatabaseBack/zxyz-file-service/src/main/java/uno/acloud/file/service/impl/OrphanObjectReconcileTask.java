package uno.acloud.file.service.impl;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.ListObjectsRequest;
import com.aliyun.sdk.service.oss2.models.ListObjectsResult;
import com.aliyun.sdk.service.oss2.models.ObjectSummary;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.common.FileObjectDeleteStatus;
import uno.acloud.common.oss.OSSProperties;
import uno.acloud.file.config.ServiceProperties;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 孤儿对象对账任务（P2-C4）。
 * <p>
 * 直传流程：客户端 PUT 对象到 OSS → 调用 upload confirm。若 confirm 失败或客户端中途放弃，
 * OSS 上会残留<b>没有任何 file_object_ref 行</b>的孤儿对象（无 file_node、无 ref 记录），
 * 是只增不减的存储成本泄漏。现有清理任务（{@link FileObjectRefCleanupTask}、
 * {@link FileObjectDeleteRetryTask}）只处理「已有 ref 行」的对象，覆盖不到从未登记的对象。
 * </p>
 * <p>
 * 本任务每日执行：
 * <ol>
 *   <li>按直传上传前缀列出 OSS 对象（分页）；</li>
 *   <li>加载 file_object_ref 中当前已知的对象键（任意状态）；</li>
 *   <li>对「无对应 ref 行」且「存在超过 24 小时」的 OSS 对象，以
 *       PENDING_DELETE 状态补一行 ref 记录，交由 {@link FileObjectDeleteRetryTask}
 *       走既有物理删除管道。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
public class OrphanObjectReconcileTask {

    /** 直传上传对象的 objectKey 前缀（与 FileUploadService.FILE_OBJECT_PREFIX = "files/" 保持一致） */
    private static final String UPLOAD_PREFIX = "files/";

    /** ListObjects 分页大小 */
    private static final long LIST_PAGE_SIZE = 1000L;

    /** 孤儿对象最小存活时长：小于该时长视为可能仍在途/未确认的上传，禁止清理（安全底线） */
    private static final long MIN_AGE_HOURS = 24L;

    private final OSSClient ossClient;
    private final OSSProperties ossProperties;
    private final FileObjectRefMapper fileObjectRefMapper;
    private final StorageProviderRegistry registry;
    private final boolean deletionEnabled;

    public OrphanObjectReconcileTask(OSSClient ossClient,
                                     OSSProperties ossProperties,
                                     FileObjectRefMapper fileObjectRefMapper,
                                     StorageProviderRegistry registry,
                                     ServiceProperties serviceProperties) {
        this.ossClient = ossClient;
        this.ossProperties = ossProperties;
        this.fileObjectRefMapper = fileObjectRefMapper;
        this.registry = registry;
        this.deletionEnabled = serviceProperties.getFileObjectDelete().isEnabled();
    }

    /** 列出结果：孤儿对象键列表 + 扫描到的 OSS 对象总数（含已有 ref 行的对象） */
    private record ListResult(List<String> orphanKeys, int scannedCount) {
        private ListResult {
            orphanKeys = orphanKeys == null ? Collections.emptyList() : orphanKeys;
        }
    }

    /**
     * 每日孤儿对象对账。
     */
    @Scheduled(fixedRate = 86400000)
    public void reconcileOrphanObjects() {
        try {
            if (!deletionEnabled) {
                log.warn("对象物理删除已禁用（app.file-object-delete.enabled=false），跳过孤儿对象对账");
                return;
            }
            String storageProvider = resolveStorageProvider();
            if (storageProvider == null) {
                log.info("默认存储提供者不支持预签名直传，跳过孤儿对象对账");
                return;
            }
            String bucket = StringUtils.trimToNull(ossProperties.getBucket());
            if (bucket == null) {
                log.warn("OSS bucket 未配置（app.oss.bucket），跳过孤儿对象对账");
                return;
            }

            Instant ageCutoff = Instant.now().minus(Duration.ofHours(MIN_AGE_HOURS));
            Set<String> knownKeys = new HashSet<>(fileObjectRefMapper.selectObjectKeysByPrefix(UPLOAD_PREFIX));
            ListResult listResult = listOrphanCandidates(bucket, UPLOAD_PREFIX, ageCutoff, knownKeys);
            if (listResult.orphanKeys().isEmpty()) {
                log.debug("孤儿对象对账完成，无孤儿对象（扫描 OSS 对象数量={}，已知对象键数量={}）",
                        listResult.scannedCount(), knownKeys.size());
                return;
            }
            int reconciled = enqueueOrphans(listResult.orphanKeys(), storageProvider);
            log.info("孤儿对象对账完成，扫描 OSS 对象数量={}，孤儿对象={}，已登记待删除={}，已知对象键数量={}",
                    listResult.scannedCount(), listResult.orphanKeys().size(), reconciled, knownKeys.size());
        } catch (Exception e) {
            log.warn("孤儿对象对账任务异常", e);
        }
    }

    /**
     * 解析对账目标存储提供者标识。
     * <p>
     * 仅当默认提供者支持预签名直传（客户端先 PUT 再 confirm，存在孤儿窗口）时对账才有意义。
     * 本地磁盘等单请求落盘提供者不存在该窗口，跳过。
     * </p>
     *
     * @return 提供者标识，无法对有孤儿窗口的提供者对账时返回 null
     */
    private String resolveStorageProvider() {
        try {
            StorageProvider provider = registry.getDefaultProvider();
            if (provider == null || !provider.supportsPresignedUpload()) {
                return null;
            }
            return provider.providerId();
        } catch (Exception e) {
            log.warn("获取默认存储提供者失败，跳过孤儿对象对账", e);
            return null;
        }
    }

    /**
     * 分页列出 OSS 上传前缀下满足条件的孤儿候选对象键。
     * <p>
     * 过滤条件（任一不满足即跳过，绝不删除）：已有 ref 行、缺少 Last-Modified 无法判断
     * 存活时长、未满 24 小时（可能为在途/未确认上传）。
     * </p>
     *
     * @param bucket      OSS bucket
     * @param prefix      对象键前缀
     * @param ageCutoff   年龄下限时间点，早于该时间点才算孤儿候选
     * @param knownKeys   已登记的对象键集合
     * @return 孤儿候选键列表与扫描总数
     */
    private ListResult listOrphanCandidates(String bucket, String prefix, Instant ageCutoff, Set<String> knownKeys) {
        List<String> orphans = new ArrayList<>();
        int scanned = 0;
        String marker = null;
        while (true) {
            ListObjectsRequest.Builder requestBuilder = ListObjectsRequest.newBuilder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .maxKeys(LIST_PAGE_SIZE);
            if (marker != null) {
                requestBuilder.marker(marker);
            }
            ListObjectsResult result = ossClient.listObjects(requestBuilder.build());
            List<ObjectSummary> contents = result.contents() == null ? Collections.emptyList() : result.contents();
            for (ObjectSummary summary : contents) {
                if (summary == null) {
                    continue;
                }
                String objectKey = StringUtils.trimToNull(summary.key());
                if (objectKey == null) {
                    continue;
                }
                scanned++;
                if (knownKeys.contains(objectKey)) {
                    continue;
                }
                Instant lastModified = summary.lastModified();
                if (lastModified == null) {
                    log.debug("OSS 对象缺少 Last-Modified，无法判断存活时长，跳过 objectKey={}", objectKey);
                    continue;
                }
                if (lastModified.isAfter(ageCutoff)) {
                    log.debug("OSS 对象未满 {} 小时，可能是未确认的在途上传，跳过 objectKey={}", MIN_AGE_HOURS, objectKey);
                    continue;
                }
                orphans.add(objectKey);
            }
            if (!Boolean.TRUE.equals(result.isTruncated())) {
                break;
            }
            marker = result.nextMarker();
            if (StringUtils.isBlank(marker)) {
                break;
            }
        }
        return new ListResult(orphans, scanned);
    }

    /**
     * 将孤儿对象逐条以 PENDING_DELETE 状态补入 file_object_ref（仅当对应行完全不存在）。
     *
     * @param orphanKeys      孤儿对象键
     * @param storageProvider 存储提供者标识
     * @return 实际新增登记的数量
     */
    private int enqueueOrphans(List<String> orphanKeys, String storageProvider) {
        int reconciled = 0;
        for (String objectKey : orphanKeys) {
            try {
                int inserted = fileObjectRefMapper.markOrphanPendingDelete(
                        objectKey, FileObjectDeleteStatus.PENDING_DELETE, storageProvider);
                if (inserted == 1) {
                    reconciled++;
                }
            } catch (Exception e) {
                log.warn("孤儿对象登记失败 objectKey={}", objectKey, e);
            }
        }
        return reconciled;
    }
}