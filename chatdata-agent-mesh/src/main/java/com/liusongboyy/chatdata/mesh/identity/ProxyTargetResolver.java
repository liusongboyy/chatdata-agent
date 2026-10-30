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

    /**
     * 客户端与用户的组合写法：{@code user:<agentClient>:<username>}。
     *
     * <h2>为什么客户端也编进 userId</h2>
     *
     * <p>实测：A2A starter 只把 {@code userId} 从 A2A message 的 metadata
     * 传进 {@code AgentRequestOptions}，**其他 metadata 键在转成 {@code Msg}
     * 时被丢弃**；而请求头也到不了 {@code streamEvents}（它在异步线程池上执行，
     * 且 starter 不转发 HTTP 头）。
     *
     * <p>所以「我是谁」和「代表谁」只能挤进这一个字段。语义上也不算别扭：
     * 那句话本身就是"agent A 代表用户 U"。
     *
     * <p>兼容只有 {@code user:<username>} 的写法：那时客户端标识为空，
     * 白名单留空才放行。
     */
    static final char CLIENT_SEPARATOR = '\u0000';

    private final MeshProperties properties;

    public ProxyTargetResolver(MeshProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析结果。
     *
     * @param agentClient 调用方标识。**换令牌时要用**——IAM 用它查授权表。
     *                    只在 resolver 内部用而不返回，会让下游拿到空值
     * @param username    被代理的用户
     */
    public record ProxyTarget(String agentClient, String username) {
    }

    /**
     * 解析要代理的目标用户。
     *
     * @param userId      A2A 消息 metadata 里的 {@code userId}。可空
     * @param agentClient 调用方自报的客户端标识（来自 {@code X-Agent-Client}）
     * @return 目标用户名；不合法时为空
     */
    public Optional<ProxyTarget> resolve(String userId, String agentClient) {
        // ① 客户端白名单（只在能拿到客户端标识时才判）。
        //
        // 拿不到 `agentClient` 有两种情况：
        //   1. 调用方只写了 `user:<username>`（没带客户端标识）→ 白名单留空才放行
        //   2. 调用方用组合写法 `user:<client>:<user>` → 白名单用嵌入的那个判
        //
        // 所以这里只处理"明确给了 agentClient"的情形，组合写法留到下面再判。
        List<String> allowed = properties.getIdentity().getAllowedClients();
        boolean clientKnown = agentClient != null && !agentClient.isBlank();
        if (!allowed.isEmpty() && clientKnown && !allowed.contains(agentClient)) {
            log.warn("A2A 调用被拒：客户端不在白名单。client={} allowed={}",
                    agentClient, allowed);
            return Optional.empty();
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
        String rest = userId.substring(USER_PREFIX.length()).trim();
        if (rest.isEmpty()) {
            log.warn("A2A 调用被拒：userId 前缀后为空。userId={}", userId);
            return Optional.empty();
        }
        // 组合写法：user:<agentClient>:<username>
        int sep = rest.indexOf(':');
        if (sep >= 0) {
            String embeddedClient = rest.substring(0, sep).trim();
            String username = rest.substring(sep + 1).trim();
            if (username.isEmpty()) {
                log.warn("A2A 调用被拒：userId 里缺用户名。userId={}", userId);
                return Optional.empty();
            }
            // 声明的客户端必须与白名单校验用的那个一致。
            // 不一致说明调用方在两处写了不同的身份——那比写错一处更可疑
            if (agentClient != null && !agentClient.isBlank()
                    && !agentClient.equals(embeddedClient)) {
                log.warn("A2A 调用被拒：userId 里的客户端({})与请求声明({})不一致",
                        embeddedClient, agentClient);
                return Optional.empty();
            }
            if (embeddedClient.isEmpty()) {
                log.warn("A2A 调用被拒：userId 里缺客户端标识。userId={}", userId);
                return Optional.empty();
            }
            // 白名单用嵌入的那个再校验一次（上面的校验用的是请求声明的，可能为空）
            List<String> allowed2 = properties.getIdentity().getAllowedClients();
            if (!allowed2.isEmpty() && !allowed2.contains(embeddedClient)) {
                log.warn("A2A 调用被拒：userId 里的客户端不在白名单。client={} allowed={}",
                        embeddedClient, allowed2);
                return Optional.empty();
            }
            // 客户端标识也要一起返回：换令牌时需要它（IAM 用它查授权表）。
            // 只在 resolver 内部解析而不再传出去，会让下游拿到空值——
            // 表现为 IAM 报 invalid_request，而错误信息里不说是哪个参数
            return Optional.of(new ProxyTarget(embeddedClient, username));
        }

        // 裸的 user:<username>：没有客户端标识。白名单非空时无法判定是哪个
        // 调用方——拒绝，让调用方改用组合写法。
        if (!allowed.isEmpty() && !clientKnown) {
            log.warn("A2A 调用被拒：白名单非空但请求未声明客户端。"
                    + "请改用 user:<agentClient>:<username> 形式。userId={}", userId);
            return Optional.empty();
        }
        return Optional.of(new ProxyTarget(agentClient == null ? "" : agentClient, rest));
    }
}
