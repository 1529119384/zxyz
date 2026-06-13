package uno.acloud.project.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.project.common.FileSpaceType;
import uno.acloud.common.Result;
import uno.acloud.common.web.CurrentUser;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.project.service.ProjectAccessGuardPort;
import uno.acloud.project.service.StorageQuotaPort;
import uno.acloud.common.permission.TeamPermissionPort;
import uno.acloud.project.vo.StorageUsageVO;

@RestController
@RequestMapping("/api/storage")
@Tag(name = "存储用量", description = "存储用量查询")
public class StorageUsageController {

    private final StorageQuotaPort storageQuotaService;
    private final TeamPermissionPort teamPermissionChecker;
    private final ProjectAccessGuardPort projectAccessGuard;

    public StorageUsageController(StorageQuotaPort storageQuotaService,
                                   TeamPermissionPort teamPermissionChecker,
                                   ProjectAccessGuardPort projectAccessGuard) {
        this.storageQuotaService = storageQuotaService;
        this.teamPermissionChecker = teamPermissionChecker;
        this.projectAccessGuard = projectAccessGuard;
    }

    @Operation(summary = "查询存储用量")
    @GetMapping("/usage")
    public Result<StorageUsageVO> usage(@CurrentUser Long userId,
                        @RequestParam(required = false) Integer spaceType,
                        @RequestParam(required = false) Long teamId,
                        @RequestParam(required = false) Long projectId) {
        Integer normalizedSpaceType = FileSpaceType.normalize(spaceType, teamId, projectId);
        if (FileSpaceType.isProject(normalizedSpaceType)) {
            projectAccessGuard.requireProjectAccess(projectId, userId);
        } else if (FileSpaceType.isTeam(normalizedSpaceType)) {
            teamPermissionChecker.check(userId, teamId, TeamPermissionCodes.TEAM_FILE_READ);
        }
        return Result.of(storageQuotaService.getUsage(userId, normalizedSpaceType, teamId, projectId));
    }
}
