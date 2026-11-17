package com.liusongboyy.chatdata.iam.serviceauth;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部身份 → 本系统用户名的解析。渠道（飞书）用。
 *
 * <h2>为什么这个端点存在</h2>
 *
 * <p>在此之前 channel <b>直连 IAM 的库表</b>查绑定关系：
 *
 * <pre>
 * SELECT username FROM chatdata_iam.iam_user
 * WHERE external_provider = ? AND external_id = ?
 * </pre>
 *
 * <p>那让 IAM 的 schema 变成了公开契约——IAM 拆库或改表结构，渠道会在
 * <b>运行时</b>才发现，而失败表现是「飞书绑定查不到」，与「IAM 表结构变了」
 * 看起来毫无关系。IAM 要独立成一个平台，这种跨库直连必须先断掉。
 *
 * <h2>鉴权：客户端凭证，不是任意调用方</h2>
 *
 * <p>这个端点回答的是「某个外部 id 有没有绑定账号」。如果对任意调用方开放，
 * 它就变成了一个可以<b>枚举探测</b>的接口：拿着 open_id 试一遍，
 * 就能知道哪些人注册过。
 *
 * <p>所以它和 {@code /iam/policy/**} 一样走客户端凭证——由
 * {@link ClientCredentialInterceptor} 在整个前缀上统一把关，本类里看不到
 * 鉴权代码是故意的。能调它的只有服务端应用，不是浏览器。
 *
 * <h2>找不到时的响应</h2>
 *
 * <p>返回 <b>404 + 空体</b>，而不是 200 + {@code {"found": false}}。
 * 后者让"未绑定"和"接口正常"共用同一个状态码，调用方容易漏判。
 * 而 404 在这里语义准确：这个外部身份没有对应的资源。
 */
@RestController
public class IdentityResolveController {

    private static final Logger log = LoggerFactory.getLogger(IdentityResolveController.class);

    private final UserMapper users;
    private final IamProperties properties;

    public IdentityResolveController(UserMapper users, IamProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    /**
     * 解析外部身份。
     *
     * @param provider   渠道标识，如 {@code feishu}
     * @param externalId 渠道侧的用户标识，如飞书 {@code open_id}
     * @return {@code {"username": "...", "userId": 1}}；未绑定或已停用时 404
     */
    @GetMapping("/iam/identity/resolve")
    public ResponseEntity<?> resolve(@RequestParam String provider,
                                     @RequestParam String externalId) {
        if (provider.isBlank() || externalId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "provider 与 externalId 必填"));
        }

        UserRow user = users.selectByExternalIdentity(
                properties.getTenant(), provider, externalId);

        if (user == null) {
            // **不区分「用户不存在」与「没绑定」**：那会给调用方一个探测账号的接口。
            // 对渠道来说两者是同一件事——需要走绑定流程
            log.debug("外部身份未绑定：provider={} externalId={}", provider, mask(externalId));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!user.active()) {
            // 停用的账号按"未绑定"处理：渠道会给绑定引导，
            // 而用户重新绑定时会撞上"账号停用"，那时提示更准确
            log.info("外部身份对应的账号已停用：provider={} username={}", provider, user.username());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", user.username());
        out.put("userId", user.id());
        return ResponseEntity.ok(out);
    }

    /** 日志里只留前后几位——完整 open_id 是可被冒用的标识。 */
    private static String mask(String id) {
        if (id == null || id.length() <= 8) {
            return "***";
        }
        return id.substring(0, 4) + "…" + id.substring(id.length() - 4);
    }
}
