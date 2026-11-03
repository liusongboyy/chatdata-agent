package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.datasource.DatasourceConnectionTester;
import com.liusongboyy.chatdata.engine.datasource.DatasourceDef;
import com.liusongboyy.chatdata.engine.datasource.DatasourceRegistry;
import com.liusongboyy.chatdata.engine.datasource.PasswordCipher;
import com.liusongboyy.chatdata.engine.admin.mapper.SemanticAdminMapper;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 数据源管理。
 *
 * <h2>密码的两条独立路径</h2>
 *
 * <p>创建与"修改密码"走 {@link #savePassword}，普通更新**不碰密码**。
 * 这不是实现方便，是语义要求：管理页回显不了密码（也不该回显），
 * 所以表单里那个空字段必须解释成"不改"，而不是"清空"。
 * 把两者合成一个 update，迟早会有人的密码被一次无关的改名操作清掉。
 *
 * <h2>删除前的占用检查</h2>
 *
 * <p>有域在用的数据源不允许删——不是数据库约束（没建外键），是这一层拦。
 * 不拦的话域会指向一个不存在的源，问数时报的是"数据源不可用"，
 * 而管理员刚刚明明删的是别的东西。
 */
public class AdminDatasourceService {

    private static final Logger log = LoggerFactory.getLogger(AdminDatasourceService.class);

    private final SemanticConfigMapper readMapper;
    private final SemanticAdminMapper writeMapper;
    private final PasswordCipher cipher;

    /**
     * 用来在配置变更后丢弃连接池。
     *
     * <p><b>不清池的后果实测过：</b>改了数据源密码，界面上「测试连接」是通的
     * （测试用表单里填的凭证现连一次），但正式查询一直 {@code Access denied}——
     * 因为 HikariCP 里还留着用旧密码建的连接，池没满就一直复用。
     *
     * <p>更糟的是错误信息被包成 {@code Connection is not available, request timed out}，
     * 把真正的 {@code Access denied} 盖掉了，排查方向会被引到"驱动缺失"上。
     */
    private final DatasourceRegistry registry;

    public AdminDatasourceService(SemanticConfigMapper readMapper,
                                  SemanticAdminMapper writeMapper,
                                  PasswordCipher cipher,
                                  DatasourceRegistry registry) {
        this.readMapper = readMapper;
        this.writeMapper = writeMapper;
        this.cipher = cipher;
        this.registry = registry;
    }

    /**
     * 配置变了就丢掉旧池。
     *
     * <p>下次使用时按新配置重建——那是一次几十毫秒的代价，
     * 比"用旧凭证一直连不上"好得多。
     */
    private void evictPool(long id) {
        if (registry == null) {
            return;   // 单元测试里不接池
        }
        try {
            registry.evict(String.valueOf(id));
        } catch (RuntimeException e) {
            // 清池失败不该让保存本身失败：配置已经落库了，
            // 最坏情况是这次请求还走旧池，重启后自然恢复
            log.warn("数据源 {} 的连接池清理失败：{}", id, e.getMessage());
        }
    }

    /** 全部数据源（含未启用的）。**不含密码**。 */
    public List<DatasourceDef> list() {
        return readMapper.selectAllDatasources();
    }

    public DatasourceDef get(long id) {
        return readMapper.selectAllDatasources().stream()
                .filter(d -> d.id() == id)
                .findFirst()
                .orElseThrow(() -> new AdminException("数据源不存在：id=" + id));
    }

    /** 新建。返回新记录的 id——管理页建完通常要立刻跳过去。 */
    public long create(DatasourceInput input, String operator) {
        DatasourceInput v = input.validated();
        requireNameAvailable(v.name(), null);

        String enc = (v.password() == null || v.password().isEmpty())
                ? null
                : cipher.encrypt(v.password());

        writeMapper.insertDatasource(v.name(), v.dbType(), v.driverClass(), v.url(),
                v.username(), enc, v.enabled(), operator);

        // 不回填自增主键（见 mapper 的说明），改按名字查回来
        return list().stream()
                .filter(d -> d.name().equals(v.name()))
                .map(DatasourceDef::id)
                .findFirst()
                .orElseThrow(() -> new AdminException("新建数据源后未能读回，请刷新列表确认"));
    }

    /** 更新。**不动密码**——见类注释。 */
    public void update(long id, DatasourceInput input, String operator) {
        get(id);   // 不存在就报错，不要静默地什么都不做
        DatasourceInput v = input.validated();
        requireNameAvailable(v.name(), id);

        writeMapper.updateDatasource(id, v.name(), v.dbType(), v.driverClass(), v.url(),
                v.username(), v.enabled(), operator);
        // URL / 用户名 / 驱动都可能变了——旧池里的连接全部作废
        evictPool(id);
        log.info("数据源已更新：id={} name={} by={}", id, v.name(), operator);
    }

    /** 单独改密码。{@code plainPassword} 为 null 或空串表示清空密码。 */
    public void savePassword(long id, String plainPassword, String operator) {
        get(id);
        String enc = (plainPassword == null || plainPassword.isEmpty())
                ? null
                : cipher.encrypt(plainPassword);
        writeMapper.updateDatasourcePassword(id, enc, operator);
        // **必须清池**：不清的话池里还留着用旧密码建立的连接，
        // 表现为「测试连接成功但查询一直 Access denied」
        evictPool(id);
        // 绝不记录密码本身，连长度都不记
        log.info("数据源密码已更新：id={} by={}", id, operator);
    }

    /** 删除。**被域引用时拒绝**——见类注释。 */
    public void delete(long id, String operator) {
        DatasourceDef def = get(id);

        int used = writeMapper.countDomainsUsingDatasource(id);
        if (used > 0) {
            throw new AdminException(
                    "数据源「%s」还被 %d 个数据域引用，不能删除。请先解除绑定。"
                            .formatted(def.name(), used));
        }

        writeMapper.deleteDatasource(id);
        evictPool(id);
        log.info("数据源已删除：id={} name={} by={}", id, def.name(), operator);
    }

    /**
     * 连接测试（已保存的数据源）。
     *
     * <p>密码留空时用已存的密码——与厂商界面的行为一致，
     * 因为管理员改完 URL 想复测时不该被迫重新输密码。
     */
    public DatasourceConnectionTester.Probe test(long id) {
        DatasourceDef def = get(id);
        String enc = readMapper.selectDatasourcePassword(id);
        String password = enc == null ? null : cipher.decrypt(enc);
        return probe(def.dbType(), def.driverClass(), def.url(), def.username(), password);
    }

    /** 连接测试（表单里的参数，尚未保存）。 */
    public DatasourceConnectionTester.Probe test(DatasourceInput input) {
        DatasourceInput v = input.validated();
        return probe(v.dbType(), v.driverClass(), v.url(), v.username(), v.password());
    }

    private DatasourceConnectionTester.Probe probe(String dbType, String driver,
                                                   String url, String username, String password) {
        DatasourceConnectionTester.Probe result =
                DatasourceConnectionTester.test(dbType, driver, url, username, password);
        DatasourceConnectionTester.logProbe(dbType, url, result);
        return result;
    }

    /**
     * 名称唯一性。
     *
     * <p>表上有唯一索引，但**在这里先查一次**：直接撞索引报的是
     * {@code Duplicate entry 'xxx' for key 'uk_ask_datasource_name'}，
     * 那是给 DBA 看的，不是给管理员看的。
     *
     * @param excludeId 更新时排除自己
     */
    private void requireNameAvailable(String name, Long excludeId) {
        boolean taken = list().stream()
                .anyMatch(d -> d.name().equals(name) && (excludeId == null || d.id() != excludeId));
        if (taken) {
            throw new AdminException("已存在同名数据源：" + name);
        }
    }
}
