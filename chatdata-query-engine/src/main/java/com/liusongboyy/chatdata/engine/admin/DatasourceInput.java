package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;

/**
 * 数据源的创建/更新入参。
 *
 * <p><strong>⚠️ 字段名必须与 record 组件逐字符一致。</strong>
 * Spring Boot 默认**静默忽略**未知字段，所以拼错一个名字的后果是
 * 那个字段变成 null、对应的校验被整个跳过——而校验代码看起来还在。
 *
 * <p>实测踩到过：请求里写 {@code driverClassName}，而本 record 的组件叫
 * {@code driverClass}，结果驱动校验从未执行，直到连接失败才从数据库的报错里
 * 看出端倪。试过用 {@code @JsonIgnoreProperties(ignoreUnknown = false)} 把它
 * 变成报错，**不生效**——该属性的默认值就是 false，显式设 false 与不设无法区分，
 * 这个注解只能开启忽略、不能关闭。
 *
 * <p>要让它报错得改全局的 {@code spring.jackson.deserialization.fail-on-unknown-properties}，
 * 而那会影响厂商那条链路（它们的 DTO 可能带额外字段），故未启用。
 * **写前端时请以本文件的组件名为准。**
 *
 * @param name        名称，唯一
 * @param dbType      类型，必须落在方言注册表里
 * @param driverClass JDBC 驱动类名（**不是** driverClassName）
 * @param url         JDBC URL
 * @param username    用户名
 * @param password    明文密码。**更新时为 null 表示不改密码**——
 *                    这个语义很重要：管理页回显不了密码（也不该回显），
 *                    所以"留空"必须解释成"不动"，而不是"清空"
 * @param enabled     是否启用
 */
public record DatasourceInput(
        String name,
        String dbType,
        String driverClass,
        String url,
        String username,
        String password,
        boolean enabled
) {

    /** 校验并归一化。**在系统边界做**——见 common/coding-style 的输入校验要求。 */
    public DatasourceInput validated() {
        require(name, "名称");
        require(dbType, "数据库类型");
        require(url, "JDBC URL");

        if (!SqlDialects.supports(dbType)) {
            throw new AdminException("暂不支持的数据源类型「%s」。已支持：%s"
                    .formatted(dbType, SqlDialects.supportedTypes()));
        }
        // 驱动缺失在这里就拦住。等到测试连接或问数时才报，
        // 用户看到的是"连接失败"而不是"缺驱动"——那是厂商那套的老问题
        if (driverClass != null && !driverClass.isBlank()) {
            try {
                Class.forName(driverClass.trim());
            } catch (ClassNotFoundException e) {
                throw new AdminException(
                        "未安装该数据库驱动（%s）。请把对应的 JDBC 驱动加入依赖后重启服务。"
                                .formatted(driverClass.trim()));
            }
        }
        if (!url.trim().toLowerCase().startsWith("jdbc:")) {
            throw new AdminException("JDBC URL 应当以 jdbc: 开头");
        }

        return new DatasourceInput(name.trim(), dbType.trim(),
                driverClass == null ? null : driverClass.trim(),
                url.trim(), username == null ? null : username.trim(),
                password, enabled);
    }

    private static void require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new AdminException(label + "不能为空");
        }
    }
}
