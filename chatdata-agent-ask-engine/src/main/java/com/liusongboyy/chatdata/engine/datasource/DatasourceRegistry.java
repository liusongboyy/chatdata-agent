package com.liusongboyy.chatdata.engine.datasource;

import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按数据源 id 取连接池。
 *
 * <h2>每个数据源一个池，懒建 + 缓存</h2>
 *
 * <p>不用 DriverManager 每次新建连接：问数是交互式场景，连接建立的开销
 * （TCP + 认证）会直接体现在用户等待时间上。
 *
 * <p>池按数据源隔离而不是共用一个：不同库的驱动、时区、字符集都可能不同，
 * 混用会让某一种库的参数污染到其它库。
 *
 * <h2>密码只在此处解密，用完即弃</h2>
 *
 * <p>池建好之后密码就不再需要——{@link HikariDataSource} 自己持有它。
 * 这个类不缓存明文密码。
 *
 * <p><strong>注意：池一旦建立就不会随配置变更重建。</strong>改了数据源密码需要重启，
 * 或者调用 {@link #evict}。这是刻意的最小实现——管理面还没做，
 * 没有"改配置"这条路径。
 */
public class DatasourceRegistry implements DatasourceResolver, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatasourceRegistry.class);

    private final SemanticConfigMapper mapper;
    private final PasswordCipher cipher;
    private final int maxPoolSize;

    /** datasourceId → 池。 */
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DatasourceRegistry(SemanticConfigMapper mapper, PasswordCipher cipher) {
        this(mapper, cipher, 5);
    }

    public DatasourceRegistry(SemanticConfigMapper mapper, PasswordCipher cipher, int maxPoolSize) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.maxPoolSize = maxPoolSize;
    }

    /**
     * 取某个数据源的连接池。
     *
     * @throws DatasourceUnavailableException 数据源不存在、未启用、或连接池建不起来
     */
    @Override
    public DataSource resolve(String datasourceId) {
        if (datasourceId == null || datasourceId.isBlank()) {
            throw new DatasourceUnavailableException("未指定数据源");
        }
        return pools.computeIfAbsent(datasourceId, this::createPool);
    }

    private HikariDataSource createPool(String datasourceId) {
        long id;
        try {
            id = Long.parseLong(datasourceId.trim());
        } catch (NumberFormatException e) {
            throw new DatasourceUnavailableException("数据源 id 非法：" + datasourceId);
        }

        DatasourceDef def = mapper.selectEnabledDatasources().stream()
                .filter(d -> d.id() == id)
                .findFirst()
                .orElseThrow(() -> new DatasourceUnavailableException(
                        "数据源 id=" + id + " 不存在或未启用"));

        // 密码单独取、单独解密，用完即弃——不放进 DatasourceDef，也就不会跟着它到处走
        String encrypted = mapper.selectDatasourcePassword(id);
        String password = encrypted == null ? null : cipher.decrypt(encrypted);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(def.url());
        cfg.setUsername(def.username());
        cfg.setPassword(password);
        if (def.driverClass() != null && !def.driverClass().isBlank()) {
            cfg.setDriverClassName(def.driverClass());
        }
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setPoolName("ask-ds-" + id);
        // 连接建立超时短一点：用户等在页面前，卡住比失败更糟
        cfg.setConnectionTimeout(10_000);
        cfg.setInitializationFailTimeout(-1);   // 建池时不试连，首次查询才连

        try {
            HikariDataSource ds = new HikariDataSource(cfg);
            log.info("已为数据源 {}（{}）建立连接池", def.name(), def.dbType());
            return ds;
        } catch (Exception e) {
            throw new DatasourceUnavailableException(
                    "数据源「" + def.name() + "」的连接池建立失败：" + e.getMessage(), e);
        }
    }

    /**
     * 丢弃某个数据源的池。
     *
     * <p>供配置变更后强制重建用。管理面落地前只有测试会调它。
     */
    public void evict(String datasourceId) {
        HikariDataSource ds = pools.remove(datasourceId);
        if (ds != null) {
            ds.close();
            log.info("已关闭数据源 {} 的连接池", datasourceId);
        }
    }

    /** 应用关闭时释放所有池。不释放会让进程无法正常退出。 */
    @Override
    public void close() {
        pools.forEach((id, ds) -> {
            try {
                ds.close();
            } catch (Exception e) {
                log.warn("关闭数据源 {} 的连接池时出错", id, e);
            }
        });
        pools.clear();
    }

    /** 数据源不可用。调用方应转成用户可读的错误，不要暴露连接串等内部信息。 */
    public static class DatasourceUnavailableException extends RuntimeException {
        public DatasourceUnavailableException(String message) {
            super(message);
        }

        public DatasourceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
