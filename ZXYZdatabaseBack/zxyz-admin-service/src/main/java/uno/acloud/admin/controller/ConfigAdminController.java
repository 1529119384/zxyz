package uno.acloud.admin.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.admin.domain.SysConfig;
import uno.acloud.admin.domain.SysConfigAudit;
import uno.acloud.admin.service.ConfigService;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.common.web.CurrentUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import uno.acloud.admin.mapper.SysConfigAuditMapper;
import uno.acloud.admin.mapper.SysConfigMapper;

import java.util.List;

/**
 * 配置管理控制器
 */
@Tag(name = "配置管理", description = "系统配置管理接口")
@RestController
@RequestMapping("/configs")
@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
public class ConfigAdminController {

    private final ConfigService configService;
    private final SysConfigMapper configMapper;
    private final SysConfigAuditMapper auditMapper;

    public ConfigAdminController(ConfigService configService,
                                 SysConfigMapper configMapper,
                                 SysConfigAuditMapper auditMapper) {
        this.configService = configService;
        this.configMapper = configMapper;
        this.auditMapper = auditMapper;
    }

    @Operation(summary = "获取所有配置列表")
    @GetMapping
    public Result<List<SysConfig>> listAll() {
        List<SysConfig> list = configMapper.selectList(
                new LambdaQueryWrapper<SysConfig>()
                        .orderByAsc(SysConfig::getConfigType, SysConfig::getConfigKey));
        return Result.of(list);
    }

    @Operation(summary = "按 key 查询单个配置值")
    @GetMapping("/{key}")
    public Result<String> getByKey(@Parameter(description = "配置键") @PathVariable String key) {
        String value = configService.get(key);
        if (value == null) {
            return Result.error(ErrorCode.NOT_FOUND, "配置键不存在: " + key, null);
        }
        return Result.of(value);
    }

    @Operation(summary = "修改配置")
    @PutMapping("/{key}")
    public Result<Void> update(@Parameter(description = "配置键") @PathVariable String key,
                               @Valid @RequestBody UpdateConfigRequest request,
                               @CurrentUser Long userId) {
        configService.update(key, request.getValue(), userId);
        return Result.success();
    }

    @Operation(summary = "获取配置变更审计日志")
    @GetMapping("/audit")
    public Result<List<SysConfigAudit>> listAuditLogs() {
        List<SysConfigAudit> list = auditMapper.selectList(
                new LambdaQueryWrapper<SysConfigAudit>()
                        .orderByDesc(SysConfigAudit::getChangedAt));
        return Result.of(list);
    }

    /**
     * 更新配置请求体
     */
    public static class UpdateConfigRequest {
        @jakarta.validation.constraints.NotBlank(message = "配置值不能为空")
        @jakarta.validation.constraints.Size(max = 4096, message = "配置值长度不能超过 4096")
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
