package uno.acloud.im.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.im.application.WsTicketService;
import uno.acloud.im.config.ImAuthContext;
import uno.acloud.satoken.AuthServicePort;

@SaCheckLogin
@RestController
@RequestMapping("/api/im")
@Tag(name = "WebSocket", description = "WebSocket 认证票据")
public class WsTicketController {

    private final WsTicketService wsTicketService;
    private final AuthServicePort authServicePort;

    public WsTicketController(WsTicketService wsTicketService, AuthServicePort authServicePort) {
        this.wsTicketService = wsTicketService;
        this.authServicePort = authServicePort;
    }

    @Operation(summary = "创建 WebSocket 认证票据")
    // 刻意不挂 @Log：响应体是 WS 认证票据本身（等价凭据），脱敏器按字段名匹配、
    // 裸字符串返回值不会被命中 ⇒ 挂 @Log 会把凭据明文写进 operate_log（R2 同型审计泄漏）。
    @PostMapping("/ws/ticket")
    public Result<String> createTicket() {
        Long userId = ImAuthContext.currentUserId();
        String saToken = authServicePort.getTokenValue();
        String ticket = wsTicketService.createTicket(userId, saToken);
        return Result.success(ticket);
    }
}
