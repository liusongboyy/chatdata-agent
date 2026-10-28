package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.admin.mapper.SemanticAdminMapper;
import com.liusongboyy.chatdata.engine.semantic.Assistant;
import com.liusongboyy.chatdata.engine.semantic.AssistantLoader;
import com.liusongboyy.chatdata.engine.semantic.AssistantRow;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 助理管理。
 *
 * <h2>绑定的域必须存在</h2>
 *
 * <p>助理指向一个不存在的域时，问数会失败在"域加载"那一步，
 * 而报错是"数据域 id=x 不存在"——管理员刚配完助理，很难把两件事联系起来。
 * 故在保存时拦住。
 */
public class AdminAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AdminAssistantService.class);

    private final SemanticConfigMapper readMapper;
    private final SemanticAdminMapper writeMapper;

    public AdminAssistantService(SemanticConfigMapper readMapper, SemanticAdminMapper writeMapper) {
        this.readMapper = readMapper;
        this.writeMapper = writeMapper;
    }

    /** 全部助理（含草稿与已下线）。 */
    public List<AssistantRow> list() {
        return readMapper.selectAllAssistants();
    }

    /** 已解析的助理，供调用方直接用。 */
    public Assistant get(long id) {
        return AssistantLoader.load(requireRow(id));
    }

    /**
     * 取原始行，不存在即报错。
     *
     * <p>{@link #get} 返回的是解析后的 {@link Assistant}，没有 {@code permJson}——
     * 而权限闸门要比对的正是库里那份原文。
     */
    private AssistantRow requireRow(long id) {
        AssistantRow row = readMapper.selectAssistantById(id);
        if (row == null) {
            throw new AdminException("助理不存在：id=" + id);
        }
        return row;
    }

    public long create(AssistantInput input, String operator) {
        AssistantInput v = input.validated();
        requireNameAvailable(v.name(), null);
        validateDomains(v);

        writeMapper.insertAssistant(v.name(), v.avatar(), v.description(), v.welcome(),
                v.domainId(), v.domainIdsJson(), v.showSql(), v.status(),
                v.questionsJson(), v.permJson(), 0, operator);

        return list().stream()
                .filter(a -> a.name().equals(v.name()))
                .map(AssistantRow::id)
                .findFirst()
                .orElseThrow(() -> new AdminException("新建助理后未能读回，请刷新列表确认"));
    }

    public void update(long id, AssistantInput input, String operator) {
        requireRow(id);   // 不存在就报错，不要静默什么都不做
        AssistantInput v = input.validated();
        requireNameAvailable(v.name(), id);
        validateDomains(v);

        writeMapper.updateAssistant(id, v.name(), v.avatar(), v.description(), v.welcome(),
                v.domainId(), v.domainIdsJson(), v.showSql(), v.status(),
                v.questionsJson(), v.permJson(), 0, operator);
        log.info("助理已更新：id={} name={} status={} by={}", id, v.name(), v.status(), operator);
    }

    public void delete(long id, String operator) {
        Assistant a = get(id);
        writeMapper.deleteAssistant(id);
        log.info("助理已删除：id={} name={} by={}", id, a.name(), operator);
    }

    /**
     * 绑定的域必须都存在。
     *
     * <p>主域与列表都要查——只查主域的话，列表里塞一个不存在的 id 要到
     * "用户选了那个域"时才炸，而那已经是几天之后了。
     */
    private void validateDomains(AssistantInput v) {
        Set<String> known = readMapper.selectAllDomains().stream()
                .map(d -> String.valueOf(d.id()))
                .collect(java.util.stream.Collectors.toSet());

        if (v.domainId() != null && !known.contains(String.valueOf(v.domainId()))) {
            throw new AdminException("主数据域不存在：id=" + v.domainId());
        }
        List<String> missing = v.domainIds().stream()
                .filter(d -> !known.contains(d))
                .toList();
        if (!missing.isEmpty()) {
            throw new AdminException(
                    "绑定了不存在的域：%s。现有域 id：%s".formatted(String.join("、", missing), known));
        }
    }

    private void requireNameAvailable(String name, Long excludeId) {
        boolean taken = list().stream()
                .anyMatch(a -> a.name().equals(name) && (excludeId == null || a.id() != excludeId));
        if (taken) {
            throw new AdminException("已存在同名助理：" + name);
        }
    }

    /** 供上层做"这个助理能不能被这个用户用"的判断。 */
    public List<Assistant> listOnline() {
        return readMapper.selectOnlineAssistants().stream().map(AssistantLoader::load).toList();
    }

    /** 某个域被哪些助理引用。删除域前可据此提示。 */
    public List<String> assistantsUsingDomain(long domainId) {
        String id = String.valueOf(domainId);
        return list().stream()
                .filter(a -> String.valueOf(a.domainId()).equals(id)
                        || (a.domainIds() != null && a.domainIds().contains(id)))
                .map(AssistantRow::name)
                .toList();
    }
}
