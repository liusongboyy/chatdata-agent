package com.liusongboyy.chatdata.iam.channel;

import com.liusongboyy.chatdata.common.core.result.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 自助绑定的 HTTP 端点。
 *
 * <h2>鉴权</h2>
 *
 * <p>全部要求已登录——绑定目标来自会话，不来自参数。
 * 过滤链对 {@code /iam/**} 的默认规则就是 {@code authenticated()}，
 * 这里没有额外放行，所以不用在 SecurityConfig 里加白名单。
 *
 * <p><b>这一点很关键：</b>如果把 {@code /iam/channel/**} 加进 permitAll，
 * 那些端点就成了无需登录的公开接口——而它们的语义是「把渠道账号绑到
 * <b>当前登录用户</b>」，没有会话时根本没有意义。
 */
@RestController
@RequestMapping("/iam/channel/bind")
public class BindController {

    private final BindService service;

    public BindController(BindService service) {
        this.service = service;
    }

    /**
     * 预览绑定。
     *
     * <p>先看再确认：直接消费票据的话，用户点开链接后按「返回」再进来就失效了。
     */
    @GetMapping
    public Result<Map<String, Object>> preview(@RequestParam(required = false) String ticket,
                                               Authentication authentication) {
        return Result.success(service.preview(ticket, authentication));
    }

    /** 确认绑定。票据在这里被消费（一次性）。 */
    @PostMapping
    public Result<Void> confirm(@RequestBody ConfirmBody body, Authentication authentication) {
        service.confirm(body == null ? null : body.ticket(), authentication);
        return Result.success();
    }

    /** 当前用户的绑定状态。「我的账号」页用。 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status(Authentication authentication) {
        return Result.success(service.status(authentication));
    }

    /** 解绑。同样只作用于当前登录用户。 */
    @PostMapping("/unbind")
    public Result<Void> unbind(Authentication authentication) {
        service.unbind(authentication);
        return Result.success();
    }

    public record ConfirmBody(String ticket) {
    }

    /**
     * 绑定失败 → 400 并透出消息。
     *
     * <p>这些消息是写给用户看的（「链接已失效」「这个飞书账号已经绑定了其他账号」），
     * 落到兜底的 500「操作失败，请稍后重试」就等于把可操作的提示变成了噪音。
     */
    @ExceptionHandler(BindService.BindFailedException.class)
    public ResponseEntity<Result<Void>> handleBindFailed(BindService.BindFailedException e) {
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }
}
