package uno.acloud.email.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import cn.dev33.satoken.annotation.SaCheckRole;
import uno.acloud.common.Result;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.email.application.EmailDispatchService;
import uno.acloud.email.application.EmailRecordQueryService;
import uno.acloud.email.application.EmailSendingAvailabilityService;
import uno.acloud.email.application.EmailServerConfigService;
import uno.acloud.email.application.VerifyCodeService;
import uno.acloud.email.dto.BatchSendEmailRequest;
import uno.acloud.email.dto.BatchSendTemplateEmailRequest;
import uno.acloud.email.dto.CheckVerifyCodeRequest;
import uno.acloud.email.dto.EmailConnectivityTestVO;
import uno.acloud.email.dto.EmailRecordPageVO;
import uno.acloud.email.vo.EmailRecordVO;
import uno.acloud.email.dto.EmailRuntimeStatusVO;
import uno.acloud.email.dto.EmailServerConfigRequest;
import uno.acloud.email.vo.EmailServerConfigVO;
import uno.acloud.email.dto.SendEmailRequest;
import uno.acloud.email.dto.SendTemplateEmailRequest;
import uno.acloud.email.dto.SendVerifyCodeRequest;

import java.util.List;

@RestController
@RequestMapping("/api/email/internal")
@Tag(name = "邮件服务（内部）", description = "邮件发送、验证码、SMTP配置管理")
@Hidden
public class EmailInternalController {

    private final EmailDispatchService emailDispatchService;
    private final VerifyCodeService verifyCodeService;
    private final EmailServerConfigService emailServerConfigService;
    private final EmailRecordQueryService emailRecordQueryService;
    private final EmailSendingAvailabilityService emailSendingAvailabilityService;

    public EmailInternalController(EmailDispatchService emailDispatchService,
                                   VerifyCodeService verifyCodeService,
                                   EmailServerConfigService emailServerConfigService,
                                   EmailRecordQueryService emailRecordQueryService,
                                   EmailSendingAvailabilityService emailSendingAvailabilityService) {
        this.emailDispatchService = emailDispatchService;
        this.verifyCodeService = verifyCodeService;
        this.emailServerConfigService = emailServerConfigService;
        this.emailRecordQueryService = emailRecordQueryService;
        this.emailSendingAvailabilityService = emailSendingAvailabilityService;
    }

    @PostMapping("/send")
    @Operation(summary = "发送邮件")
    public Result<Long> send(@Valid @RequestBody SendEmailRequest request) {
        Long recordId = emailDispatchService.send(
                request == null ? null : request.getRecipient(),
                request == null ? null : request.getSubject(),
                request == null ? null : request.getContentHtml(),
                request == null ? null : request.getBusinessType(),
                request == null ? null : request.getBusinessId(),
                request == null ? null : request.getScheduledTime()
        );
        return Result.of(recordId);
    }

    @PostMapping("/send-batch")
    @Operation(summary = "批量发送邮件")
    public Result<List<Long>> sendBatch(@Valid @RequestBody BatchSendEmailRequest request) {
        return Result.of(emailDispatchService.sendBatch(
                request == null ? null : request.getRecipients(),
                request == null ? null : request.getSubject(),
                request == null ? null : request.getContentHtml(),
                request == null ? null : request.getBusinessType(),
                request == null ? null : request.getBusinessId(),
                request == null ? null : request.getScheduledTime()
        ));
    }

    @PostMapping("/send-template")
    @Operation(summary = "按模板发送邮件")
    public Result<Long> sendByTemplate(@Valid @RequestBody SendTemplateEmailRequest request) {
        Long recordId = emailDispatchService.sendByTemplate(
                request == null ? null : request.getRecipient(),
                request == null ? null : request.getTemplateCode(),
                request == null ? null : request.getVariables(),
                request == null ? null : request.getBusinessType(),
                request == null ? null : request.getBusinessId(),
                request == null ? null : request.getScheduledTime()
        );
        return Result.of(recordId);
    }

    @PostMapping("/send-template-batch")
    @Operation(summary = "按模板批量发送邮件")
    public Result<List<Long>> sendBatchByTemplate(@Valid @RequestBody BatchSendTemplateEmailRequest request) {
        return Result.of(emailDispatchService.sendBatchByTemplate(
                request == null ? null : request.getRecipients(),
                request == null ? null : request.getTemplateCode(),
                request == null ? null : request.getVariables(),
                request == null ? null : request.getBusinessType(),
                request == null ? null : request.getBusinessId(),
                request == null ? null : request.getScheduledTime()
        ));
    }

    @PostMapping("/verify-codes/send")
    @Operation(summary = "发送验证码")
    public Result<Void> sendVerifyCode(@Valid @RequestBody SendVerifyCodeRequest request, HttpServletRequest servletRequest) {
        verifyCodeService.sendCode(
                request == null ? null : request.getEmail(),
                request == null ? null : request.getScene(),
                resolveRequestIp(request, servletRequest)
        );
        return Result.success();
    }

    @PostMapping("/verify-codes/check")
    @Operation(summary = "校验验证码")
    public Result<Void> checkVerifyCode(@Valid @RequestBody CheckVerifyCodeRequest request) {
        verifyCodeService.checkCode(
                request == null ? null : request.getEmail(),
                request == null ? null : request.getScene(),
                request == null ? null : request.getCode()
        );
        return Result.success();
    }

    @PostMapping("/scheduled-batches")
    @Operation(summary = "创建计划邮件批次")
    public Result<List<Long>> createScheduledBatch(@Valid @RequestBody BatchSendEmailRequest request) {
        return sendBatch(request);
    }

    @GetMapping("/server-configs")
    @Operation(summary = "查询SMTP配置列表")
    @SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
    public Result<List<EmailServerConfigVO>> listServerConfigs() {
        return Result.of(emailServerConfigService.listConfigs());
    }

    @GetMapping("/server-configs/current")
    @Operation(summary = "获取当前SMTP配置")
    @SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
    public Result<EmailServerConfigVO> getCurrentServerConfig() {
        return Result.of(emailServerConfigService.getCurrentConfig().orElse(null));
    }

    @GetMapping("/runtime-status")
    @Operation(summary = "获取邮件服务运行状态")
    @SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
    public Result<EmailRuntimeStatusVO> getRuntimeStatus() {
        return Result.of(emailSendingAvailabilityService.getRuntimeStatus());
    }

    @PostMapping("/server-configs")
    @Operation(summary = "创建SMTP配置")
    @SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
    public Result<EmailServerConfigVO> createServerConfig(@Valid @RequestBody EmailServerConfigRequest request) {
        return Result.of(emailServerConfigService.createConfig(request));
    }

    @PutMapping("/server-configs/{id}")
    @Operation(summary = "更新SMTP配置")
    @SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
    public Result<EmailServerConfigVO> updateServerConfig(@PathVariable Long id, @Valid @RequestBody EmailServerConfigRequest request) {
        return Result.of(emailServerConfigService.updateConfig(id, request));
    }

    @PostMapping("/server-configs/{id}/test")
    @Operation(summary = "测试SMTP配置连通性")
    @SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
    public Result<EmailConnectivityTestVO> testServerConfig(@PathVariable Long id) {
        return Result.of(emailServerConfigService.testConfig(id));
    }

    @PostMapping("/server-configs/{id}/activate")
    @Operation(summary = "激活SMTP配置")
    @SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
    public Result<EmailServerConfigVO> activateServerConfig(@PathVariable Long id) {
        return Result.of(emailServerConfigService.activateConfig(id));
    }

    @GetMapping("/records")
    @Operation(summary = "查询邮件发送记录")
    @SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
    public Result<EmailRecordPageVO> listEmailRecords(@RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String recipient,
                                                      @RequestParam(required = false) String businessType,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer pageSize) {
        return Result.of(emailRecordQueryService.listRecords(status, recipient, businessType, page, pageSize));
    }

    @GetMapping("/records/{id}")
    @Operation(summary = "获取邮件发送详情")
    @SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
    public Result<EmailRecordVO> getEmailRecord(@PathVariable Long id) {
        return Result.of(emailRecordQueryService.getRecord(id));
    }

    private String resolveRequestIp(SendVerifyCodeRequest request, HttpServletRequest servletRequest) {
        if (request != null && request.getRequestIp() != null && !request.getRequestIp().isBlank()) {
            return request.getRequestIp().trim();
        }
        return servletRequest == null ? null : servletRequest.getRemoteAddr();
    }
}
