package com.liusongboyy.chatdata.engine.datasource.dialect;

/**
 * StarRocks。
 *
 * <h2>为什么继承 MySQL 而不是自成一套</h2>
 *
 * <p>StarRocks 兼容 MySQL 协议（驱动用 {@code com.mysql.cj.jdbc.Driver}），
 * 且 SQL 方言高度对齐：标识符反引号、{@code DATE_FORMAT} 的 {@code %Y-%m}
 * 模式语言、{@code DATE_ADD(..., INTERVAL n MONTH)}、{@code TIMESTAMPDIFF}
 * 全都与 MySQL 一致。
 *
 * <p>这些是问数渲染器真正用到的<b>全部</b>时间函数——不是"大概一样"，
 * 是逐个对过。
 *
 * <h2>为什么仍要独立类</h2>
 *
 * <p>StarRocks 是 OLAP，将来必然分叉：它对 {@code APPROX_COUNT_DISTINCT}、
 * 物化视图、窗口函数的细节与 MySQL 不同，而这些正是分析型问数会用到的。
 * 现在独立成类，将来覆盖某个方法即可，不必回头拆继承关系。
 *
 * <p>与 {@link DorisDialect} 同一取舍——两者都源自 MySQL 血统。
 */
public class StarRocksDialect extends MySqlDialect {

    @Override
    public String id() {
        return "StarRocks";
    }

    @Override
    public String displayName() {
        return "StarRocks";
    }
}
