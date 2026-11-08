package com.liusongboyy.chatdata.iam.embed;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 宿主系统与助理的授权关系。
 *
 * <h2>为什么单独一张表，而不是靠 scope</h2>
 *
 * <p>scope 是字符串集合（{@code ask.read}），表达的是"能做什么动作"。
 * 而这里要表达的是"能问哪个助理"——是<b>数据范围</b>，不是动作。
 * 塞进 scope 会得到 {@code ask.assistant.2200} 这种码，
 * 而助理是随时可增删的配置，把它编进 scope 等于让 OAuth 客户端注册
 * 跟着业务配置一起变。
 *
 * <h2>为什么不是「助理自己声明哪些宿主能用它」</h2>
 *
 * <p>那需要改 {@code ask_assistant} 表，而助理属于 ask 模块，IAM 不该反向依赖它。
 * 授权关系放在 IAM 侧——<b>谁能用谁，是身份问题</b>。
 */
@Mapper
public interface EmbedClientMapper {

    /**
     * 这个宿主能不能问这个助理。
     *
     * @return 授权记录存在且未撤销时返回 1，否则 0
     */
    @Select("""
            SELECT COUNT(*) FROM iam_embed_client_assistant
            WHERE client_id = #{clientId} AND assistant_id = #{assistantId}
              AND revoked_at IS NULL
            """)
    int countGrant(@Param("clientId") String clientId,
                   @Param("assistantId") String assistantId);

    /** 便捷判断。 */
    default boolean allows(String clientId, String assistantId) {
        if (clientId == null || assistantId == null) {
            return false;
        }
        return countGrant(clientId, assistantId) > 0;
    }
}
