package com.liusongboyy.chatdata.mesh.identity;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 判定一次 A2A 调用要「代表谁」。
 *
 * <h2>两道判定</h2>
 *
 * <ol>
 *   <li><b>客户端白名单</b>——软校验，防误接。{@code X-Agent-Client} 头可伪造，
 *       它<b>不是安全边界</b>。</li>
 *   <li><b>userId 的形态</b>——必须是 {@code user:<username>}。
 *       其他形态（{@code agent:xxx}、裸用户名）一律拒绝，
 *       少一种形态就少一条要保护的路径。</li>
 * </ol>
 *
 * <h2>真正的边界不在这里</h2>
 *
 * <p>「用户是否授权过这个 agent」由 IAM 判——它在换令牌时查
 * {@code iam_agent_grant}。本类只做**格式与白名单**的早拒绝：
 * 让明显不合法的请求在发 HTTP 之前就失败，不用等一次往返。
 *
 * <p>为什么不在这里也查授权表：那需要 mesh 直连 IAM 的库。多一个库连接、
 * 多一处与 IAM 的耦合，而 IAM 换令牌时反正要查一次。**判据只留一处**，
 * 出问题时的行为才是可预测的。
 */
@Component
public class ProxyTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(ProxyTargetResolver.class);

    /** 「代表某人」的前缀。 */
    static final String USER_PREFIX = "user:";

    private final MeshProperties properties;

    public ProxyTargetResolver(MeshProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析要代理的目标用户。
     *
     * @param userId      A2A 消息 metadata 里的 {@code userId}。可空
     * @param agentClient 调用方自报的客户端标识（来自 {@code X-Agent-Client}）
     * @return 目标用户名；不合法时为空
     */
    public Optional<String> resolve(String userId, String agentClient) {
        // ① 客户端白名单。留空表示不校验（仅限本地开发）
        List<String> allowed = properties.getIdentity().getAllowedClients();
        if (!allowed.isEmpty()) {
            if (agentClient == null || agentClient.isBlank() || !allowed.contains(agentClient)) {
                log.warn("A2A 调用被拒：客户端不在白名单。client={} allowed={}",
                        agentClient, allowed);
                return Optional.empty();
            }
        }

        // ② userId 形态
        if (userId == null || userId.isBlank()) {
            // 没有 userId = 不是代理请求。本期不支持「agent 以自己的身份查数」，
            // 所以直接拒绝而不是降级成某种默认身份
            log.warn("A2A 调用被拒：缺少 userId（无法确定代表谁）");
            return Optional.empty();
        }
        if (!userId.startsWith(USER_PREFIX)) {
            // agent:xxx 之类将来可能支持，现在明确拒绝——
            // 少一种形态就少一条要保护的路径
            log.warn("A2A 调用被拒：userId 前缀不支持。userId={} 只支持 {}<username>",
                    userId, USER_PREFIX);
            return Optional.empty();
        }
        String username = userId.substring(USER_PREFIX.length()).trim();
        if (username.isEmpty()) {
            log.warn("A2A 调用被拒：userId 前缀后为空。userId={}", userId);
            return Optional.empty();
        }
        return Optional.of(username);
    }
}
