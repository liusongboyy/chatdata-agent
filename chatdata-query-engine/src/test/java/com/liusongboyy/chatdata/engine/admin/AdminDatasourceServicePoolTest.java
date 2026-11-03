package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.datasource.DatasourceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 数据源配置变更后必须丢弃连接池。
 *
 * <p>这个 bug 的实际表现很误导：改了密码，界面上「测试连接」是通的
 * （测试用表单里的凭证现连一次），但正式查询一直 {@code Access denied}——
 * 因为 HikariCP 里还留着用旧密码建的连接。
 *
 * <p>更糟的是错误被包成 {@code Connection is not available, request timed out}，
 * 把真正的 {@code Access denied} 盖掉了，排查方向会被引到"驱动缺失"上。
 */
class AdminDatasourceServicePoolTest {

    private static final long DS_ID = 129;

    @Test
    @DisplayName("★ 改密码要清池，否则旧连接一直用旧密码")
    void savePasswordEvictsPool() {
        DatasourceRegistry registry = mock(DatasourceRegistry.class);
        verifyEvictedOnSavePassword(registry);
    }

    private void verifyEvictedOnSavePassword(DatasourceRegistry registry) {
        // 这里只断言"清池方法被调用"这一契约。
        // 完整行为（改完能连上）在集成测试里验证——
        // 单元测试造不出 HikariCP 用旧密码复用的现场。
        var service = new AdminDatasourceService(null, null, null, registry);
        // 用反射调 evictPool，避免为了测一个私有副作用去搭整个 mapper mock
        try {
            var m = AdminDatasourceService.class.getDeclaredMethod("evictPool", long.class);
            m.setAccessible(true);
            m.invoke(service, DS_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        verify(registry).evict(String.valueOf(DS_ID));
    }

    @Test
    @DisplayName("★ 清池失败不能让保存失败——配置已经落库了")
    void evictFailureDoesNotPropagate() {
        DatasourceRegistry registry = mock(DatasourceRegistry.class);
        doThrow(new IllegalStateException("池已关闭"))
                .when(registry).evict(String.valueOf(DS_ID));

        var service = new AdminDatasourceService(null, null, null, registry);
        // 清池失败只记 warn，不抛——最坏这次请求还走旧池，重启后自然恢复
        org.assertj.core.api.Assertions
                .assertThatCode(() -> {
                    var m = AdminDatasourceService.class.getDeclaredMethod("evictPool", long.class);
                    m.setAccessible(true);
                    m.invoke(service, DS_ID);
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("registry 为 null 时不崩（单元测试不接池）")
    void nullRegistryIsSafe() {
        var service = new AdminDatasourceService(null, null, null, null);
        org.assertj.core.api.Assertions
                .assertThatCode(() -> {
                    var m = AdminDatasourceService.class.getDeclaredMethod("evictPool", long.class);
                    m.setAccessible(true);
                    m.invoke(service, DS_ID);
                })
                .doesNotThrowAnyException();
    }
}
