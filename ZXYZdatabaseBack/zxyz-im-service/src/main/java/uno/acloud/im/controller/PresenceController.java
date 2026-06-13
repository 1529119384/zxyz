package uno.acloud.im.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.im.application.UserPresenceService;
import uno.acloud.im.config.ImAuthContext;
import uno.acloud.im.vo.UserPresenceVO;

import java.util.Arrays;
import java.util.List;

@SaCheckLogin
@RestController
@RequestMapping("/api/im/presence")
@Tag(name = "在线状态", description = "用户在线状态查询")
public class PresenceController {

    private final UserPresenceService userPresenceService;

    public PresenceController(UserPresenceService userPresenceService) {
        this.userPresenceService = userPresenceService;
    }

    @Operation(summary = "获取我的在线状态")
    @GetMapping("/me")
    public Result<UserPresenceVO> getMyPresence() {
        return Result.of(userPresenceService.getPresence(ImAuthContext.currentUserId()));
    }

    @Operation(summary = "查询用户在线状态")
    @GetMapping("/users")
    public Result<List<UserPresenceVO>> listUserPresence(@RequestParam String userIds) {
        List<Long> parsedUserIds = Arrays.stream(userIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Long::valueOf)
                .toList();
        return Result.of(userPresenceService.listPresence(parsedUserIds));
    }
}
