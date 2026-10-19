package uno.acloud.user.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.user.dto.DefaultTeamRequest;
import uno.acloud.user.dto.InternalBatchUserRequest;
import uno.acloud.user.dto.InternalCreateTeamUserRequest;
import uno.acloud.user.entity.User;
import uno.acloud.user.service.impl.InternalUserService;
import uno.acloud.user.service.impl.UserProfileService;
import uno.acloud.vo.InternalUserInfoVO;
import uno.acloud.user.vo.UserInfoVO;
import uno.acloud.user.vo.UserQuotaVO;
import uno.acloud.user.vo.UserSearchItemVO;

import java.util.List;

/**
 * 内部服务用户查询 API，仅供其他微服务通过 INTERNAL_SERVICE_TOKEN 调用。
 *
 * <p>65/66 号 🟠-5 重构（三轮点名收口）：业务全部下沉 {@link InternalUserService}，
 * Controller 不再直注 Mapper、deleteUser 业务规则移入服务层 —— 照
 * InternalProjectController 的 Port 范式。</p>
 */
@Hidden
@RestController
@RequestMapping("/api/internal/users")
@Tag(name = "用户管理（内部）", description = "内部服务用户查询 API")
public class InternalUserController {

    private final UserProfileService userProfileService;
    private final InternalUserService internalUserService;

    public InternalUserController(UserProfileService userProfileService,
                                  InternalUserService internalUserService) {
        this.userProfileService = userProfileService;
        this.internalUserService = internalUserService;
    }

    @Operation(summary = "获取用户基本信息")
    @GetMapping("/{userId}/info")
    public Result<InternalUserInfoVO> getUserInfo(@PathVariable Long userId) {
        User user = userProfileService.getUserById(userId);
        if (user == null) {
            // 契约：用户不存在返回 code=1 + data=null（调用方 UserQueryClient 按 data 判空）
            return Result.of(null);
        }
        return Result.of(new InternalUserInfoVO(user.getId(), user.getUsername()));
    }

    @Operation(summary = "根据ID获取用户")
    @GetMapping("/{id}")
    public Result<UserInfoVO> getUserById(@PathVariable Long id) {
        User user = internalUserService.getUserById(id);
        if (user == null) {
            return Result.of(null);
        }
        return Result.of(internalUserService.toUserInfo(user));
    }

    @Operation(summary = "批量获取用户")
    @PostMapping("/batch")
    public Result<List<UserInfoVO>> getUsersByIds(@Valid @RequestBody InternalBatchUserRequest request) {
        List<User> users = internalUserService.getUsersByIds(request.getUserIds());
        return Result.of(users.stream().map(internalUserService::toUserInfo).toList());
    }

    @Operation(summary = "创建团队用户")
    @PostMapping("/create-team-user")
    public Result<UserInfoVO> createTeamUser(@Valid @RequestBody InternalCreateTeamUserRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword());
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setDefaultTeamId(request.getDefaultTeamId());
        user.setCreateTime(java.time.LocalDateTime.now());
        return Result.of(internalUserService.createTeamUser(user));
    }

    @Operation(summary = "更新默认团队")
    @PutMapping("/{id}/default-team")
    public Result<Void> updateDefaultTeam(@PathVariable Long id, @Valid @RequestBody DefaultTeamRequest request) {
        internalUserService.updateDefaultTeam(id, request.getTeamId());
        return Result.success();
    }

    @Operation(summary = "获取所有用户ID")
    @GetMapping("/all-ids")
    public Result<List<Long>> getAllUserIds() {
        return Result.of(internalUserService.listAllUserIds());
    }

    @Operation(summary = "获取已验证邮箱列表")
    @GetMapping("/verified-emails")
    public Result<List<String>> getVerifiedEmails() {
        return Result.of(internalUserService.listVerifiedEmails());
    }

    @Operation(summary = "获取用户配额")
    @GetMapping("/{id}/quota")
    public Result<UserQuotaVO> getUserQuota(@PathVariable Long id) {
        UserQuotaVO quota = internalUserService.getQuotaVO(id);
        if (quota == null) {
            // 契约：配额不存在返回 code=1 + data=null
            return Result.of(null);
        }
        return Result.of(quota);
    }

    @Operation(summary = "搜索用户")
    @GetMapping("/search")
    public Result<List<UserSearchItemVO>> searchUsers(@org.springframework.web.bind.annotation.RequestParam String keyword) {
        return Result.of(userProfileService.searchUsers(keyword));
    }

    @Operation(summary = "清除权限缓存")
    @PostMapping("/{id}/clear-permission-cache")
    public Result<Void> clearPermissionCache(@PathVariable Long id) {
        internalUserService.clearPermissionCache(id);
        return Result.success();
    }

    @Operation(summary = "删除用户（事务回滚补偿用）")
    @DeleteMapping("/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        internalUserService.deleteUser(id);
        return Result.success();
    }

}
