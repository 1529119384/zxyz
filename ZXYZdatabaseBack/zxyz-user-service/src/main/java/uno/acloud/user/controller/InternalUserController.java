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
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.dto.DefaultTeamRequest;
import uno.acloud.user.dto.InternalBatchUserRequest;
import uno.acloud.user.dto.InternalCreateTeamUserRequest;
import uno.acloud.user.entity.User;
import uno.acloud.user.entity.UserQuota;
import uno.acloud.user.infrastructure.client.TeamServiceMemberClient;
import uno.acloud.user.mapper.UserEntityMapper;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.mapper.UserQuotaMapper;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.user.satoken.SaTokenAuthSessionService;
import uno.acloud.user.service.impl.UserProfileService;
import uno.acloud.user.vo.UserInfoVO;
import uno.acloud.user.vo.UserQuotaVO;
import uno.acloud.vo.InternalUserInfoVO;

import java.util.List;

import uno.acloud.user.vo.UserSearchItemVO;

/**
 * 内部服务用户查询 API，仅供其他微服务通过 INTERNAL_SERVICE_TOKEN 调用。
 */
@Hidden
@RestController
@RequestMapping("/api/internal/users")
@Tag(name = "用户管理（内部）", description = "内部服务用户查询 API")
public class InternalUserController {

    private final UserProfileService userProfileService;
    private final UserMapper userMapper;
    private final UserQuotaMapper userQuotaMapper;
    private final UserEntityMapper userEntityMapper;
    private final AuthServicePort authServicePort;
    private final TeamServiceMemberClient teamServiceMemberClient;

    public InternalUserController(UserProfileService userProfileService, UserMapper userMapper, UserQuotaMapper userQuotaMapper, UserEntityMapper userEntityMapper, AuthServicePort authServicePort, TeamServiceMemberClient teamServiceMemberClient) {
        this.userProfileService = userProfileService;
        this.userMapper = userMapper;
        this.userQuotaMapper = userQuotaMapper;
        this.userEntityMapper = userEntityMapper;
        this.authServicePort = authServicePort;
        this.teamServiceMemberClient = teamServiceMemberClient;
    }

    @Operation(summary = "获取用户基本信息")
    @GetMapping("/{userId}/info")
    @SuppressWarnings("unchecked")
    public Result<InternalUserInfoVO> getUserInfo(@PathVariable Long userId) {
        User user = userProfileService.getUserById(userId);
        if (user == null) {
            return (Result<InternalUserInfoVO>) (Result<?>) Result.success();
        }
        return Result.of(new InternalUserInfoVO(user.getId(), user.getUsername()));
    }

    @Operation(summary = "根据ID获取用户")
    @GetMapping("/{id}")
    @SuppressWarnings("unchecked")
    public Result<UserInfoVO> getUserById(@PathVariable Long id) {
        User user = userMapper.getById(id);
        if (user == null) {
            return (Result<UserInfoVO>) (Result<?>) Result.success();
        }
        return Result.of(toUserInfo(user));
    }

    @Operation(summary = "批量获取用户")
    @PostMapping("/batch")
    public Result<List<UserInfoVO>> getUsersByIds(@Valid @RequestBody InternalBatchUserRequest request) {
        List<Long> userIds = request.getUserIds();
        List<User> users = userMapper.listByIds(userIds);
        return Result.of(users.stream().map(this::toUserInfo).toList());
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
        userMapper.insertTeamUser(user);
        return Result.of(toUserInfo(user));
    }

    @Operation(summary = "更新默认团队")
    @PutMapping("/{id}/default-team")
    public Result<Void> updateDefaultTeam(@PathVariable Long id, @Valid @RequestBody DefaultTeamRequest request) {
        userMapper.updateDefaultTeam(id, request.getTeamId());
        return Result.success();
    }

    @Operation(summary = "获取所有用户ID")
    @GetMapping("/all-ids")
    public Result<List<Long>> getAllUserIds() {
        return Result.of(userMapper.listAllUserIds());
    }

    @Operation(summary = "获取已验证邮箱列表")
    @GetMapping("/verified-emails")
    public Result<List<String>> getVerifiedEmails() {
        return Result.of(userMapper.listVerifiedEmails());
    }

    @Operation(summary = "获取用户配额")
    @GetMapping("/{id}/quota")
    @SuppressWarnings("unchecked")
    public Result<UserQuotaVO> getUserQuota(@PathVariable Long id) {
        UserQuota quota = userQuotaMapper.getByUserId(id);
        if (quota == null) {
            return (Result<UserQuotaVO>) (Result<?>) Result.success();
        }
        return Result.of(userEntityMapper.toUserQuotaVO(quota));
    }

    @Operation(summary = "搜索用户")
    @GetMapping("/search")
    public Result<List<UserSearchItemVO>> searchUsers(@org.springframework.web.bind.annotation.RequestParam String keyword) {
        return Result.of(userProfileService.searchUsers(keyword));
    }

    @Operation(summary = "清除权限缓存")
    @PostMapping("/{id}/clear-permission-cache")
    public Result<Void> clearPermissionCache(@PathVariable Long id) {
        authServicePort.deleteSessionAttribute(id, SaTokenAuthSessionService.EXTRA_ROLE);
        authServicePort.deleteSessionAttribute(id, SaTokenAuthSessionService.EXTRA_PERMISSION);
        return Result.success();
    }

    @Operation(summary = "删除用户（事务回滚补偿用）")
    @DeleteMapping("/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        List<Long> teamIds = teamServiceMemberClient.listUserTeamIds(id);
        if (teamIds != null && !teamIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "用户仍关联 " + teamIds.size() + " 个团队，无法删除，请先从各团队移除用户");
        }
        userMapper.deleteById(id);
        return Result.success();
    }

    private UserInfoVO toUserInfo(User user) {
        return userEntityMapper.toUserInfoVO(user);
    }
}
