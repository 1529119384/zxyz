package uno.acloud.project.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemRoleCodes;

import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
@RestController
@RequestMapping("/api/admin/database")
@Tag(name = "数据库管理（管理后台）", description = "数据库维护状态查询")
public class AdminDatabaseController {

    @Operation(summary = "获取数据库维护状态")
    @GetMapping("/maintenance/status")
    public Result<Map<String, Object>> getMaintenanceStatus() {
        return Result.of(Map.of(
                "enabled", false,
                "confirmationText", "确认导入数据库",
                "targets", List.of()
        ));
    }
}
