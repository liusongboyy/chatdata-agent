package com.liusongboyy.chatdata.engine.model.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模型的读写。
 *
 * <h2>为什么参数逐个传而不是传对象</h2>
 *
 * <p>MyBatis 靠 {@code getX()} 反射读参数，而 record 的访问器是 {@code x()}——
 * 读不到。传 record 会得到一个"所有字段都是 null"的 insert，
 * 而它不报错。与 {@code SemanticAdminMapper} 同一取舍。
 *
 * <h2>为什么价格用 {@code BigDecimal}</h2>
 *
 * <p>单价是「元/百万 token」，量级从 0.05 到 72。用 double 累加几千次调用后
 * 会有可见误差，而这是要跟账单对账的数字。
 */
@Mapper
public interface ModelMapper {

    @Select("""
            SELECT model_key, display_name, description, base_url, model_name,
                   allowed_roles, price_input, price_cached, price_output, priced_at,
                   is_default, enabled
            FROM ask_model
            ORDER BY is_default DESC, id
            """)
    List<ModelRow> selectAll();

    @Select("""
            SELECT model_key, display_name, description, base_url, model_name,
                   allowed_roles, price_input, price_cached, price_output, priced_at,
                   is_default, enabled
            FROM ask_model
            WHERE model_key = #{key}
            """)
    ModelRow selectByKey(@Param("key") String key);

    /** 密文单独查——列表页**不该带出密钥**，哪怕它是密文。 */
    @Select("SELECT api_key_enc FROM ask_model WHERE model_key = #{key}")
    String selectApiKeyEnc(@Param("key") String key);

    @Insert("""
            INSERT INTO ask_model
              (model_key, display_name, description, base_url, api_key_enc, model_name,
               allowed_roles, price_input, price_cached, price_output, priced_at,
               is_default, enabled, created_by)
            VALUES
              (#{key}, #{displayName}, #{description}, #{baseUrl}, #{apiKeyEnc}, #{modelName},
               #{allowedRoles}, #{priceInput}, #{priceCached}, #{priceOutput}, #{pricedAt},
               #{isDefault}, #{enabled}, #{createdBy})
            """)
    int insert(@Param("key") String key,
               @Param("displayName") String displayName,
               @Param("description") String description,
               @Param("baseUrl") String baseUrl,
               @Param("apiKeyEnc") String apiKeyEnc,
               @Param("modelName") String modelName,
               @Param("allowedRoles") String allowedRoles,
               @Param("priceInput") BigDecimal priceInput,
               @Param("priceCached") BigDecimal priceCached,
               @Param("priceOutput") BigDecimal priceOutput,
               @Param("pricedAt") java.time.LocalDateTime pricedAt,
               @Param("isDefault") boolean isDefault,
               @Param("enabled") boolean enabled,
               @Param("createdBy") String createdBy);

    @Update("""
            UPDATE ask_model SET
              display_name = #{displayName},
              description = #{description},
              base_url = #{baseUrl},
              model_name = #{modelName},
              allowed_roles = #{allowedRoles},
              price_input = #{priceInput},
              price_cached = #{priceCached},
              price_output = #{priceOutput},
              priced_at = #{pricedAt},
              is_default = #{isDefault},
              enabled = #{enabled}
            WHERE model_key = #{key}
            """)
    int update(@Param("key") String key,
               @Param("displayName") String displayName,
               @Param("description") String description,
               @Param("baseUrl") String baseUrl,
               @Param("modelName") String modelName,
               @Param("allowedRoles") String allowedRoles,
               @Param("priceInput") BigDecimal priceInput,
               @Param("priceCached") BigDecimal priceCached,
               @Param("priceOutput") BigDecimal priceOutput,
               @Param("pricedAt") java.time.LocalDateTime pricedAt,
               @Param("isDefault") boolean isDefault,
               @Param("enabled") boolean enabled);

    /** 换密钥。与改其它字段分开——保存时不传密钥表示"保持不变"。 */
    @Update("UPDATE ask_model SET api_key_enc = #{enc} WHERE model_key = #{key}")
    int updateApiKey(@Param("key") String key, @Param("enc") String enc);

    /**
     * 把除自己之外的默认标记清掉。
     *
     * <p><b>全局只能有一个默认。</b>靠应用层"先查再改"在并发下会漏
     * （两个请求同时读到"没有默认" → 都设成默认）。用一条 UPDATE 保证原子。
     */
    @Update("UPDATE ask_model SET is_default = 0 WHERE model_key <> #{key} AND is_default = 1")
    int clearOtherDefaults(@Param("key") String key);

    /**
     * 删掉默认标记（当这个模型被停用或不再是默认时）。
     */
    @Update("UPDATE ask_model SET is_default = 0 WHERE model_key = #{key}")
    int clearDefault(@Param("key") String key);

    @Update("UPDATE ask_model SET enabled = #{enabled} WHERE model_key = #{key}")
    int setEnabled(@Param("key") String key, @Param("enabled") boolean enabled);

    @Select("SELECT COUNT(*) FROM ask_model WHERE enabled = 1")
    int countEnabled();

    /**
     * 某个域绑定的模型键。
     *
     * <p>模型模块**只读**域表的这一列，不依赖 {@code SemanticConfigMapper}——
     * 那会让模型模块反向依赖语义模块，而语义模块已经依赖模型了（要取实例）。
     */
    @Select("SELECT model_key FROM ask_domain WHERE id = #{id}")
    String selectDomainModelKey(@Param("id") long id);

    /**
     * 一条模型记录（不含密钥）。
     *
     * <p>{@code allowedRoles} 是 JSON 原文，由 Service 解析——
     * MyBatis 直接映射成 {@code List<String>} 需要额外的 TypeHandler，
     * 而这里只有一处用，不值得。
     */
    record ModelRow(
            String modelKey,
            String displayName,
            String description,
            String baseUrl,
            String modelName,
            String allowedRoles,
            BigDecimal priceInput,
            BigDecimal priceCached,
            BigDecimal priceOutput,
            java.time.LocalDateTime pricedAt,
            boolean isDefault,
            boolean enabled
    ) {
    }
}
