package com.liusongboyy.chatdata.engine.datasource.dialect;

/**
 * Apache Doris。
 *
 * <p>Doris 兼容 MySQL 协议（厂商的驱动映射表里 Doris 用的就是
 * {@code com.mysql.cj.jdbc.Driver}），SQL 方言也高度对齐 MySQL，故直接继承。
 *
 * <p><strong>继承而不是复用的理由：</strong>两者将来一定会分叉——Doris 是 OLAP，
 * 它对窗口函数、近似聚合（{@code APPROX_COUNT_DISTINCT}）、物化视图的支持与 MySQL 不同。
 * 现在写成独立类，将来覆盖某个方法即可，不必回头拆。
 */
public class DorisDialect extends MySqlDialect {

    @Override
    public String id() {
        return "Doris";
    }

    @Override
    public String displayName() {
        return "Apache Doris";
    }
}
