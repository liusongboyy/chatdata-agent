package com.liusongboyy.chatdata.iam.channel;

import com.liusongboyy.chatdata.iam.security.IamUserDetails;
import com.liusongboyy.chatdata.iam.security.IamUserDetailsService;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 自助绑定：把某个渠道账号绑到<b>当前登录用户</b>。
 *
 * <h2>绑定方向由会话决定，不由票据决定</h2>
 *
 * <p>票据只说明「有人想在飞书里绑账号」。绑到谁，看的是这次请求携带的
 * Web 端会话——也就是 {@code Authentication}。<b>绝不从请求参数读取目标用户</b>：
 * 那等于让攻击者指定要接管哪个账号。
 *
 * <h2>为什么要 transaction</h2>
 *
 * <p>唯一索引冲突时 {@code updateExternalIdentity} 会抛 {@code DuplicateKeyException}。
 * 事务保证这条 UPDATE 不留下半截状态。
 */
@Service
public class BindService {

    private static final Logger log = LoggerFactory.getLogger(BindService.class);

    private final BindTicketStore tickets;
    private final UserMapper users;
    private final IamUserDetailsService userDetails;

    public BindService(BindTicketStore tickets, UserMapper users,
                       IamUserDetailsService userDetails) {
        this.tickets = tickets;
        this.users = users;
        this.userDetails = userDetails;
    }

    /**
     * 预览：这次绑定会绑什么。
     *
     * <p>不消费票据——用户可能只是点开看看，直接消费会让「返回」再进来就失效。
     */
    public Map<String, Object> preview(String ticket, Authentication authentication) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (authentication == null || !authentication.isAuthenticated()) {
            out.put("authenticated", false);
            return out;
        }
        out.put("authenticated", true);
        out.put("currentUser", authentication.getName());

        Optional<BindTicketStore.Ticket> t = tickets.peek(ticket);
        if (t.isEmpty()) {
            out.put("valid", false);
            out.put("message", "链接已失效或已被使用，请回飞书重新发送消息获取新链接。");
            return out;
        }
        out.put("valid", true);
        out.put("channel", t.get().channel());
        // open_id 只显示前几位：完整值对人没有意义，泄露也没好处
        out.put("externalIdMasked", mask(t.get().externalId()));
        out.put("displayName", t.get().displayName());
        return out;
    }

    /**
     * 确认绑定。
     *
     * @param ticket         绑定票据
     * @param authentication 当前 Web 会话。<b>绑定目标只来自这里</b>
     * @throws BindFailedException 票据无效、已绑定他人、用户状态异常
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String ticket, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BindFailedException("请先登录再完成绑定。");
        }
        BindTicketStore.Ticket t = tickets.consume(ticket)
                .orElseThrow(() -> new BindFailedException(
                        "链接已失效或已被使用，请回飞书重新发送消息获取新链接。"));

        IamUserDetails me = userDetails.loadPrincipal(authentication.getName());
        if (me.orgId() == null) {
            // 不拦：没有组织的人也能绑定，只是他看不到任何行数据。
            // 但记一条日志——这通常意味着账号建得不完整
            log.info("绑定渠道身份的用户没有组织：user={}", me.username());
        }

        try {
            users.updateExternalIdentity(me.userId(), t.channel(), t.externalId());
        } catch (DuplicateKeyException e) {
            // 唯一索引 uk_iam_user_external 拦下：这个渠道账号已绑给别人。
            // **不覆盖**——覆盖等于把别人的绑定抢走
            throw new BindFailedException(
                    "这个 " + channelLabel(t.channel()) + " 账号已经绑定了其他问数账号，请联系管理员处理。");
        }

        log.info("自助绑定成功：user={} channel={} externalId={}",
                me.username(), t.channel(), t.externalId());
    }

    /**
     * 解除绑定。
     *
     * <p>目标同样只来自会话，不来自参数。
     */
    @Transactional(rollbackFor = Exception.class)
    public void unbind(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BindFailedException("请先登录。");
        }
        IamUserDetails me = userDetails.loadPrincipal(authentication.getName());
        // 已经是解绑状态时也照常执行：幂等，用户重复点不会报错
        users.updateExternalIdentity(me.userId(), null, null);
        log.info("解除渠道绑定：user={}", me.username());
    }

    /** 当前用户的绑定状态。「我的账号」页用。 */
    public Map<String, Object> status(Authentication authentication) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (authentication == null || !authentication.isAuthenticated()) {
            out.put("authenticated", false);
            return out;
        }
        IamUserDetails me = userDetails.loadPrincipal(authentication.getName());
        out.put("authenticated", true);
        out.put("username", me.username());
        out.put("displayName", me.displayName());
        // loadPrincipal 不带 external*，这里单独查一次——
        // 它是低频调用（用户主动打开这个页），不值得为它改 principal 的加载路径
        Map<String, Object> identity = users.selectExternalIdentity(me.userId());
        out.put("externalProvider", identity == null ? null : identity.get("provider"));
        out.put("externalIdMasked", identity == null ? null : mask((String) identity.get("externalId")));
        return out;
    }

    private static String channelLabel(String channel) {
        return switch (channel) {
            case "feishu" -> "飞书";
            case "dingtalk" -> "钉钉";
            case "wecom" -> "企业微信";
            default -> channel;
        };
    }

    private static String mask(String externalId) {
        if (externalId == null || externalId.length() <= 8) {
            return externalId == null ? null : "****";
        }
        return externalId.substring(0, 6) + "…" + externalId.substring(externalId.length() - 4);
    }

    /** 绑定失败。消息是**给用户看的**。 */
    public static class BindFailedException extends RuntimeException {
        public BindFailedException(String message) {
            super(message);
        }
    }
}
