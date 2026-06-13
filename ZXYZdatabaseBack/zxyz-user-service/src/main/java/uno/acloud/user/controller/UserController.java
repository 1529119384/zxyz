package uno.acloud.user.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;
import uno.acloud.common.web.CurrentUser;
import uno.acloud.exception.BusinessException;
import uno.acloud.common.oss.AvatarUploadSignRequest;
import uno.acloud.common.oss.OssSignInfo;
import uno.acloud.user.config.CookieHelper;
import uno.acloud.user.dto.ContactVerifyRequest;
import uno.acloud.user.service.impl.AccountLinkingService;
import uno.acloud.user.service.impl.AuthService;
import uno.acloud.user.service.impl.ContactVerificationService;
import uno.acloud.user.service.impl.LoginRateLimiter;
import uno.acloud.user.service.impl.RegisterRateLimiter;
import uno.acloud.user.dto.DefaultTeamRequest;
import uno.acloud.user.dto.EmailBindRequest;
import uno.acloud.user.dto.LinkedAccountTrustRequest;
import uno.acloud.user.dto.LoginRequest;
import uno.acloud.user.dto.PasswordChangeRequest;
import uno.acloud.user.dto.PhoneBindRequest;
import uno.acloud.user.dto.RegisterRequest;
import uno.acloud.user.dto.UserSettingsRequest;
import uno.acloud.user.service.impl.UserProfileService;
import uno.acloud.user.vo.AccountSwitchVO;
import uno.acloud.user.vo.ContactVerificationCodeVO;
import uno.acloud.user.vo.CurrentUserVO;
import uno.acloud.user.vo.LinkedAccountVO;
import uno.acloud.user.vo.LoginVO;
import uno.acloud.user.vo.UserSearchItemVO;
import uno.acloud.satoken.AuthServicePort;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/users")
@Tag(name = "用户管理", description = "用户注册、登录、资料管理")
public class UserController {

    private final AuthService authService;
    private final UserProfileService userProfileService;
    private final ContactVerificationService contactVerificationService;
    private final AccountLinkingService accountLinkingService;
    private final CookieHelper cookieHelper;
    private final LoginRateLimiter loginRateLimiter;
    private final RegisterRateLimiter registerRateLimiter;
    private final AuthServicePort authServicePort;

    public UserController(AuthService authService,
                          UserProfileService userProfileService,
                          ContactVerificationService contactVerificationService,
                          AccountLinkingService accountLinkingService,
                          CookieHelper cookieHelper,
                          LoginRateLimiter loginRateLimiter,
                          RegisterRateLimiter registerRateLimiter,
                          AuthServicePort authServicePort) {
        this.authService = authService;
        this.userProfileService = userProfileService;
        this.contactVerificationService = contactVerificationService;
        this.accountLinkingService = accountLinkingService;
        this.cookieHelper = cookieHelper;
        this.loginRateLimiter = loginRateLimiter;
        this.registerRateLimiter = registerRateLimiter;
        this.authServicePort = authServicePort;
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse response) {
        String ip = httpRequest.getRemoteAddr();
        loginRateLimiter.checkAndIncrement(ip, request.getUsername());
        log.info("用户 {} 请求登录", request.getUsername());
        String token = authService.login(request);
        log.info("用户 {} 登录成功", request.getUsername());
        cookieHelper.setAuthCookies(response, token);
        return Result.of(new LoginVO(token, "Bearer", true));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletResponse response) {
        authServicePort.logout();
        cookieHelper.clearAuthCookies(response);
        return Result.success();
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody RegisterRequest request,
                                   HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        registerRateLimiter.checkAndIncrement(ip);
        log.info("用户 {} 请求注册", request.getUsername());
        int result = authService.register(request);
        if (result > 0) {
            log.info("用户 {} 注册成功", request.getUsername());
            return Result.of("注册成功");
        }

        log.error("用户 {} 注册失败", request.getUsername());
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败");
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<CurrentUserVO> getCurrentUser(@CurrentUser Long userId) {
        CurrentUserVO currentUser = userProfileService.getCurrentUser(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
        return Result.of(currentUser);
    }

    @Operation(summary = "获取用户设置")
    @GetMapping("/settings")
    public Result<CurrentUserVO> getUserSettings(@CurrentUser Long userId) {
        return Result.of(userProfileService.getUserSettings(userId));
    }

    @Operation(summary = "更新用户设置")
    @PatchMapping("/settings")
    public Result<CurrentUserVO> updateSettings(@CurrentUser Long userId, @Valid @RequestBody UserSettingsRequest request) {
        return Result.of(userProfileService.updateSettings(userId, request));
    }

    @Operation(summary = "获取头像上传签名")
    @PostMapping("/avatar/upload-sign")
    public Result<OssSignInfo> getAvatarUploadSign(@CurrentUser Long userId, @Valid @RequestBody AvatarUploadSignRequest request) {
        return Result.of(userProfileService.getAvatarUploadSign(userId, request));
    }

    @Operation(summary = "修改密码")
    @PatchMapping("/password")
    public Result<CurrentUserVO> changePassword(@CurrentUser Long userId, @Valid @RequestBody PasswordChangeRequest request) {
        return Result.of(userProfileService.changePassword(userId, request));
    }

    @Operation(summary = "绑定邮箱")
    @PatchMapping("/email")
    public Result<CurrentUserVO> bindEmail(@CurrentUser Long userId, @Valid @RequestBody EmailBindRequest request) {
        return Result.of(contactVerificationService.bindEmail(userId, request));
    }

    @Operation(summary = "绑定手机号")
    @PatchMapping("/phone")
    public Result<CurrentUserVO> bindPhone(@CurrentUser Long userId, @Valid @RequestBody PhoneBindRequest request) {
        return Result.of(contactVerificationService.bindPhone(userId, request));
    }

    @Operation(summary = "设置默认团队")
    @PatchMapping("/default-team")
    public Result<CurrentUserVO> setDefaultTeam(@CurrentUser Long userId, @Valid @RequestBody DefaultTeamRequest request) {
        return Result.of(userProfileService.setDefaultTeam(userId, request));
    }

    @Operation(summary = "发送邮箱验证码")
    @PostMapping("/email/verification-code")
    public Result<ContactVerificationCodeVO> createEmailVerificationCode(@CurrentUser Long userId, HttpServletRequest request) {
        return Result.of(contactVerificationService.createEmailVerificationCode(userId, request == null ? null : request.getRemoteAddr()));
    }

    @Operation(summary = "发送手机验证码")
    @PostMapping("/phone/verification-code")
    public Result<ContactVerificationCodeVO> createPhoneVerificationCode(@CurrentUser Long userId) {
        return Result.of(contactVerificationService.createPhoneVerificationCode(userId));
    }

    @Operation(summary = "验证联系方式")
    @PostMapping("/contact/verify")
    public Result<CurrentUserVO> verifyContact(@CurrentUser Long userId, @Valid @RequestBody ContactVerifyRequest request) {
        return Result.of(contactVerificationService.verifyContact(userId, request));
    }

    @Operation(summary = "查询关联账号")
    @GetMapping("/linked-accounts")
    public Result<List<LinkedAccountVO>> listLinkedAccounts(@CurrentUser Long userId) {
        return Result.of(accountLinkingService.listLinkedAccounts(userId));
    }

    @Operation(summary = "信任关联账号")
    @PostMapping("/linked-accounts/{targetUserId}/trust")
    public Result<LinkedAccountVO> trustLinkedAccount(@CurrentUser Long userId,
                                     @org.springframework.web.bind.annotation.PathVariable Long targetUserId,
                                     @Valid @RequestBody LinkedAccountTrustRequest request) {
        return Result.of(accountLinkingService.trustLinkedAccount(userId, targetUserId, request));
    }

    @Operation(summary = "切换关联账号")
    @PostMapping("/linked-accounts/{targetUserId}/switch")
    public Result<AccountSwitchVO> switchLinkedAccount(@CurrentUser Long userId,
                                                             @org.springframework.web.bind.annotation.PathVariable Long targetUserId,
                                                             HttpServletResponse response) {
        AccountSwitchVO switchResult = accountLinkingService.switchLinkedAccount(userId, targetUserId);
        cookieHelper.setAuthCookies(response, switchResult.getToken());
        return Result.of(switchResult);
    }

    @Operation(summary = "搜索用户")
    @GetMapping("/search")
    public Result<List<UserSearchItemVO>> searchUsers(@RequestParam String keyword) {
        authServicePort.checkLogin();
        return Result.of(userProfileService.searchUsers(keyword));
    }
}
