package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.config.ServiceProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 调用 project-service 的存储配额校验 HTTP 客户端。
 * <p>继承 {@link AbstractServiceClient}，获得 Resilience4j 重试+熔断保护。</p>
 *
 * <p>错误处理契约：</p>
 * <ul>
 *   <li>配额不足(409) → BAD_REQUEST「存储空间不足」（用户可自行清理后重试）；</li>
 *   <li><b>403 → 与 409 区分开（F8 修复）</b>：403 是<b>内部服务鉴权失败</b>
 *       （token 未配置/不匹配、来源服务不在白名单），属<b>部署/配置问题</b>，用户清理空间也无济于事。
 *       此前 403 被一并映射成「存储空间不足」，把运维故障误导成用户的容量问题 ——
 *       用户会反复清理文件却始终失败，而真正的原因（内部 token 配错）被这句话掩盖。</li>
 *   <li>其余异常 → SYSTEM_ERROR。</li>
 * </ul>
 *
 * <p>P1-B2：本接口的 {@code data} 字段是「有效存储上限字节数（NULL=不限制）」，
 * 由调用方埋入配额台账（usage_ledger.storage_limit），供写入同事务的原子扣减守卫使用。
 * 因此本方法把解析出的上限返回，不再丢弃。</p>
 */
@Slf4j
@Component
public class ProjectStorageCheckClient extends AbstractServiceClient {

    public ProjectStorageCheckClient(RestClient restClient,
                                     ServiceProperties serviceProperties,
                                     ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getProjectService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "项目服务(配额校验)";
    }

    /**
     * 校验目标空间的存储配额是否足够。
     *
     * @param userId     用户 ID
     * @param teamId     团队 ID（个人空间为 null）
     * @param spaceType  空间类型
     * @param projectId  项目 ID（非项目空间为 null）
     * @param totalSize  本次操作所需总字节数
     * @return 有效存储上限字节数；NULL 表示不限制（未配置或解析失败）
     * @throws BusinessException 配额不足或服务不可用时抛出
     */
    public Long checkQuota(Long userId, Long teamId, Integer spaceType, Long projectId, long totalSize) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("teamId", teamId);
        body.put("spaceType", spaceType);
        body.put("projectId", projectId);
        body.put("totalSize", totalSize);
        try {
            JsonNode response = postJson("/api/internal/storage/check-quota", body);
            return parseStorageLimit(response);
        } catch (BusinessException e) {
            // ⚠️ F8 关键：AbstractServiceClient 会把**所有 4xx** 用上游响应体里的
            // code/msg 原样包成 BusinessException（见其 executeWithResilience 的
            // `throw parseErrorResponse(...)`），因此 403/409 不会以
            // RestClientResponseException 的形态到达这里 —— 必须按**上游业务码**分流。
            //
            // 上游 project-service 的 StorageQuotaService 用 FILE_STATE_INVALID(4090) 表示配额不足，
            // GlobalExceptionHandler 把它映射成 HTTP 409；403 则是「内部服务鉴权失败」，
            // 由鉴权过滤器产生，**不带** 4090 业务码。
            //
            // 修复前的缺陷：403 与 409 都被报成「存储空间不足」⇒ 用户面对 token 配错
            // 这种部署故障时会反复清理文件却始终失败，真正原因被完全掩盖。
            if (e.getErrorCode() == ErrorCode.FILE_STATE_INVALID) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "存储空间不足，请清理后重试");
            }
            if (e.getErrorCode() == ErrorCode.NO_PERMISSION) {
                log.error("存储配额校验被拒(403)，通常是内部服务令牌或调用方白名单配置问题: {}", e.getMessage());
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "存储配额校验服务鉴权失败，请联系管理员");
            }
            // 其余业务码（含上游 4000 参数校验失败）属调用方/服务端问题，统一按系统错误处理；
            // 上游原始 message 可能含类名/SQL/内网地址，只写日志、绝不透传。
            log.error("调用存储配额校验失败(code={}): {}", e.getErrorCode(), e.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "存储配额校验服务异常，请稍后重试");
        } catch (RestClientResponseException e) {
            // 兜底：直连场景（未经 AbstractServiceClient 包装）仍走 HTTP 状态码分流
            int status = e.getStatusCode().value();
            if (status == 409) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "存储空间不足，请清理后重试");
            }
            if (status == 403) {
                log.error("存储配额校验被拒(403)，通常是内部服务令牌或调用方白名单配置问题，"
                        + "status={}, body={}", status, e.getResponseBodyAsString(), e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "存储配额校验服务鉴权失败，请联系管理员");
            }
            log.error("调用存储配额校验失败(status={}): {}", status, e.getResponseBodyAsString(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "存储配额校验服务异常，请稍后重试");
        } catch (Exception e) {
            log.error("调用存储配额校验失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "存储配额校验服务不可用，请稍后重试");
        }
    }

    /**
     * 解析 check-quota 响应体的 {@code data} 字段（有效存储上限字节数）。
     * <p>解析失败（字段缺失/类型异常）返回 null 而不抛异常 —— 与上传路径
     * FileUploadService.upsertLedgerLimit 的容错口径一致：limit 为 NULL 时台账守卫退化为
     * 「不限制」，由小时级对账任务（UsageLedgerReconcileTask）兜底，不阻断业务操作。</p>
     */
    private Long parseStorageLimit(JsonNode response) {
        if (response == null) {
            return null;
        }
        try {
            JsonNode data = response.path("data");
            if (data.isMissingNode() || data.isNull() || !data.canConvertToLong()) {
                return null;
            }
            return data.asLong();
        } catch (Exception ex) {
            log.warn("解析存储配额上限失败，本次按不限制处理", ex);
            return null;
        }
    }
}
