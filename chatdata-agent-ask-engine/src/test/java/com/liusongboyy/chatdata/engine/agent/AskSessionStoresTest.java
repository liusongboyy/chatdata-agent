package com.liusongboyy.chatdata.engine.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库名解析的测试。
 *
 * <p>这件事值得单独测，是因为它错了的表现**不是报错而是沉默**：
 * 库名解析成 agentscope 的默认值（{@code agentscope}）时，
 * 状态会被写进另一个库——表建出来了、读写也成功，
 * 只是所有会话在业务库里都查不到，排查时看的是错的库。
 */
class AskSessionStoresTest {

    /** 桩数据源：连接只回答 getCatalog，其余方法都不该被调到。 */
    private static DataSource dataSourceReturningCatalog(String catalog, SQLException failure) {
        InvocationHandler connectionHandler = (proxy, method, args) -> {
            if (method.getName().equals("getCatalog")) {
                if (failure != null) {
                    throw failure;
                }
                return catalog;
            }
            if (method.getName().equals("close")) {
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        };

        return (DataSource) Proxy.newProxyInstance(
                AskSessionStoresTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return Proxy.newProxyInstance(
                                AskSessionStoresTest.class.getClassLoader(),
                                new Class<?>[]{Connection.class},
                                connectionHandler);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    @DisplayName("配了库名就用配的，不去连库")
    void configuredWins() {
        DataSource boom = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (p, m, a) -> {
                    throw new AssertionError("配了库名就不该去连库");
                });

        assertThat(AskSessionStores.resolveDatabase(boom, "jimureport")).isEqualTo("jimureport");
    }

    @Test
    @DisplayName("库名两侧空白被去掉——配置里多打一个空格不该变成一个不同的库")
    void configuredIsTrimmed() {
        assertThat(AskSessionStores.resolveDatabase(null, "  ask_db  ")).isEqualTo("ask_db");
    }

    @Test
    @DisplayName("没配库名：读连接上实际连着的那个库")
    void fallsBackToCatalog() {
        DataSource ds = dataSourceReturningCatalog("jimureport", null);

        assertThat(AskSessionStores.resolveDatabase(ds, null)).isEqualTo("jimureport");
    }

    @Test
    @DisplayName("空白配置按没配处理——空字符串与 null 同义")
    void blankIsTreatedAsUnset() {
        DataSource ds = dataSourceReturningCatalog("jimureport", null);

        assertThat(AskSessionStores.resolveDatabase(ds, "")).isEqualTo("jimureport");
        assertThat(AskSessionStores.resolveDatabase(ds, "   ")).isEqualTo("jimureport");
    }

    @Test
    @DisplayName("驱动不报告当前库：明确报错并点出该配哪个键，不猜一个库名")
    void nullCatalogFailsLoudly() {
        DataSource ds = dataSourceReturningCatalog(null, null);

        assertThatThrownBy(() -> AskSessionStores.resolveDatabase(ds, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chatdata.engine.session.database");
    }

    @Test
    @DisplayName("连不上：报错里带原始原因，否则只能看到「读库名失败」这一层")
    void connectionFailureIsReportedWithCause() {
        DataSource ds = dataSourceReturningCatalog(null, new SQLException("Access denied"));

        assertThatThrownBy(() -> AskSessionStores.resolveDatabase(ds, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Access denied");
    }
}
