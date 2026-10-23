package com.liusongboyy.chatdata.engine.chat;

import com.liusongboyy.chatdata.engine.agent.DslSource;
import com.liusongboyy.chatdata.engine.audit.AskAuditRecorder;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialect;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.agent.AskDeniedException;
import com.liusongboyy.chatdata.engine.permission.AssistantPermission;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.semantic.Assistant;
import com.liusongboyy.chatdata.engine.semantic.AssistantLoader;
import com.liusongboyy.chatdata.engine.semantic.AssistantRow;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.DomainLoader;
import com.liusongboyy.chatdata.engine.semantic.DomainRow;
import com.liusongboyy.chatdata.engine.semantic.DomainTableRow;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按域装配 {@link AskService}。
 *
 * <h2>域路由尚未实现</h2>
 *
 * <p>PRD §8 的链路是「助理路由（domain_ids → 选域）」，那需要助理与多域配置。
 * 当前实现：指定 {@code domainId} 就用它，否则取**第一个启用中的域**。
 * 单域场景下行为正确，多域时会路由到第一个——**这是已知的临时行为**，
 * 不是"看起来像路由"的假实现：它没有假装在选，只是取第一个。
 *
 * <h2>每次请求都重新加载语义模型</h2>
 *
 * <p>没有缓存。理由是配置变更必须立即生效——语义建模改了一个字段的别名，
 * 用户下一次提问就该用新名字。加缓存需要一套失效机制，而当前域的数量很小，
 * 重新加载的成本远低于缓存失效写错的成本。等域数量上来了再说。
 */
@Component
public class AskServiceFactory {

    private static final Logger log = LoggerFactory.getLogger(AskServiceFactory.class);

    private final SemanticConfigMapper mapper;
    private final DslSource dslSource;
    private final SqlExecutor sqlExecutor;
    private final AskAuditRecorder auditRecorder;
    /** 直连模式要模型**自己写 SQL**，故工厂需要直接持有它。同样是惰性代理。 */
    private final io.agentscope.core.model.Model askEngineModel;
    private final com.liusongboyy.chatdata.engine.datasource.DatasourceResolver datasources;
    private final com.liusongboyy.chatdata.engine.raw.RawSchemaBuilder rawSchemas;

    /**
     * @param dslSource <strong>必须 {@code @Lazy}</strong>：它背后是 LLM 模型，
     *                  而模型需要 api-key。若在此急切注入，没配 key 的环境
     *                  （开发、CI）会**整个应用起不来**——但管理面、语义建模
     *                  这些功能根本不需要模型。
     *                  <p>只在 Bean 定义上标 {@code @Lazy} 不够：构造器注入是急切解析，
     *                  会把惰性绕过去。惰性必须标在注入点上。
     */
    public AskServiceFactory(SemanticConfigMapper mapper,
                             @Lazy DslSource dslSource,
                             SqlExecutor sqlExecutor,
                             AskAuditRecorder auditRecorder,
                             @Lazy io.agentscope.core.model.Model askEngineModel,
                             com.liusongboyy.chatdata.engine.datasource.DatasourceResolver datasources,
                             com.liusongboyy.chatdata.engine.raw.RawSchemaBuilder rawSchemas) {
        this.mapper = mapper;
        this.dslSource = dslSource;
        this.sqlExecutor = sqlExecutor;
        this.auditRecorder = auditRecorder;
        this.askEngineModel = askEngineModel;
        this.datasources = datasources;
        this.rawSchemas = rawSchemas;
    }

    /**
     * 为指定域装配服务。
     *
     * @param domainId 域 id；为空时取第一个启用中的域
     */
    public AskFacade forDomain(String domainId) {
        DomainRow row = loadDomainRow(domainId);

        List<DomainTableRow> tables = mapper.selectTablesByDomain(row.id());
        String dbType = mapper.selectEnabledDatasources().stream()
                .filter(d -> row.datasourceId() != null && d.id() == row.datasourceId())
                .map(com.liusongboyy.chatdata.engine.datasource.DatasourceDef::dbType)
                .findFirst()
                .orElse(null);

        Domain domain = DomainLoader.load(row, tables,
                mapper.selectTermsByDomain(row.id()),
                mapper.selectTopicsByDomain(row.id()),
                dbType);

        // **按模式分流**。分流放在装配层，控制器只依赖 AskFacade——
        // 让控制器去 if (mode == RAW) 的话，迟早会漏一处，而漏掉的那一处
        // 一定在权限或审计上
        if (domain.isRaw()) {
            return new RawAskService(domain, askEngineModel, datasources, rawSchemas, auditRecorder,
                    // 范例与术语一样按 domain 存：跨域照抄 SQL 必然查不到表
                    mapper.selectSqlExamplesByDomain(row.id()));
        }

        SemanticModel model = new SemanticModel(domain);
        SqlDialect dialect = SqlDialects.of(domain.dbType());
        return new AskService(model, dialect, dslSource, sqlExecutor, auditRecorder);
    }

    /**
     * 域绑定的数据源 id。
     *
     * <p>问数主链路**不需要**它：SQL 执行由 {@code SqlExecutor} 按
     * {@code domain.datasourceId()} 自己解析。这里暴露出来是给旁路用的——
     * 评测要用同一个数据源跑 gold SQL，否则比的不是同一个库。
     *
     * <p>不返回 {@code null}：评测拿一个空数据源去执行只会得到一句
     * 与真实原因无关的"数据源不可用"。
     */
    public String datasourceIdOf(String domainId) {
        DomainRow row = loadDomainRow(domainId);
        if (row.datasourceId() == null) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "数据域 id=" + row.id() + " 没有绑定数据源，无法生成可执行的 SQL");
        }
        return String.valueOf(row.datasourceId());
    }

    private DomainRow loadDomainRow(String domainId) {
        if (domainId != null && !domainId.isBlank()) {
            long id;
            try {
                id = Long.parseLong(domainId.trim());
            } catch (NumberFormatException e) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "数据域 id 非法：" + domainId);
            }
            DomainRow row = mapper.selectDomainById(id);
            if (row == null) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "数据域 id=" + id + " 不存在");
            }
            return row;
        }

        return mapper.selectEnabledDomains().stream()
                .findFirst()
                .orElseThrow(() -> new SemanticException(
                        SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "没有启用中的数据域，无法问数"));
    }

    /**
     * 某个域能问哪些字段。
     *
     * <p>存在的理由：用户会问「你能问哪些数据」。那**不是数据问题，是能力问题**——
     * 让模型把它翻译成 SQL 只会得到一个语法正确但没有意义的查询，
     * 然后失败。界面应当直接回答它。
     *
     * <p>这里复用 {@link #forDomain} 的加载逻辑，只是不调模型——
     * {@code dslSource} 是惰性代理，传进去不会触发它的创建。
     */
    public Capabilities capabilities(String domainId) {
        // 走 AskFacade：直连模式的 availableFields 是空列表（它没有"登记过的字段"），
        // 界面据此不显示字段引导。若在这里自己建 SemanticModel，直连域会直接建不出来
        AskFacade service = forDomain(domainId);
        return new Capabilities(service.domainName(), service.availableFields());
    }

    /**
     * 域的能力说明。
     *
     * @param domainName 域显示名
     * @param fields     可问的字段名（业务名）
     */
    public record Capabilities(String domainName, List<String> fields) {
    }

    /**
     * 为指定助理装配服务。
     *
     * <h2>助理层的闸门与域层**不同**</h2>
     *
     * <p>ADR-0002 实测：
     * <table>
     *   <tr><th>层</th><th>判据</th><th>失败表现</th></tr>
     *   <tr><td>域</td><td>grantRoles/grantUsers 有内容，或 isPublic</td><td>返回 0 行</td></tr>
     *   <tr><td>助手</td><td><b>enabled=true 且主体在名单内</b></td><td>明确错误</td></tr>
     * </table>
     *
     * <p>两者不可统一：域看"有没有授权内容"，助手看"开关是否打开"。
     * 故这里**单独判一次**，不能因为域层过了就放行。
     *
     * <p><strong>但助理层不是数据保护点</strong>——它只决定"这个助理可不可用"。
     * 数据保护由域层的行/列规则承担。
     *
     * @param assistantId 助理 id；为空时退化为直接按域问数（供 API 直连场景）
     * @param domainId    指定域；为空时用助理的主域
     * @param ctx         判定主体
     */
    public AskFacade forAssistant(String assistantId, String domainId, PermissionContext ctx) {
        if (assistantId == null || assistantId.isBlank()) {
            // 不经过助理：直连 API 的场景。域层闸门仍会生效
            return forDomain(domainId);
        }

        AssistantRow row = loadAssistantRow(assistantId);
        Assistant assistant = loadAssistant(row);

        if (!assistant.isOnline()) {
            throw new AskDeniedException(PermissionDecision.Reason.ASSISTANT_NOT_GRANTED,
                    "该助理尚未上线");
        }

        AssistantPermission perm = assistant.permission();
        if (!perm.grantsAccess(ctx)) {
            log.warn("助理层拒绝：assistant={} user={} roles={} enabled={}",
                    assistant.name(), ctx.userId(), ctx.roles(), perm.enabled());
            throw new AskDeniedException(PermissionDecision.Reason.ASSISTANT_NOT_GRANTED,
                    "您没有该助理的访问权限");
        }

        // 多域时**不替用户猜**：用显式指定的域，否则用主域。
        // 真正的智能路由（让模型判断问题该落哪个域）是独立的一块工作，
        // 尚未实现——这里不假装有
        String resolved = (domainId != null && !domainId.isBlank())
                ? domainId
                : assistant.primaryDomainId();

        if (resolved == null || resolved.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "助理「%s」没有绑定数据域，无法问数".formatted(assistant.name()));
        }
        if (assistant.hasMultipleDomains() && (domainId == null || domainId.isBlank())) {
            log.info("助理「{}」绑定了 {} 个域，未指定时使用主域 {}（多域智能路由尚未实现）",
                    assistant.name(), assistant.allDomainIds().size(), resolved);
        }

        return forDomain(resolved);
    }

    private AssistantRow loadAssistantRow(String assistantId) {
        long id;
        try {
            id = Long.parseLong(assistantId.trim());
        } catch (NumberFormatException e) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "助理 id 非法：" + assistantId);
        }
        AssistantRow row = mapper.selectAssistantById(id);
        if (row == null) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "助理 id=" + id + " 不存在");
        }
        return row;
    }

    /**
     * 加载助理。
     *
     * <p><strong>只此一处</strong>：{@code forAssistant} 与 {@code listUsableAssistants}
     * 都必须经它加载。两处各写一遍的话迟早只改一处，而表现是"列表里看得见、
     * 点进去被拒"（或反过来）——两边各自的单测都绿，接起来是错的。
     */
    private Assistant loadAssistant(AssistantRow row) {
        return AssistantLoader.load(row);
    }

    /**
     * 当前主体**能用**的助理。
     *
     * <p>两个条件都要满足：已上线，且助理层的权限放行。
     *
     * <p><strong>只返回能用的</strong>，不返回全部再让前端过滤——
     * 把不能用的助理名字发给前端，等于告诉一个无权限的人"存在哪些助理"。
     * 虽然那不是数据泄露，但没必要。
     *
     * <p>返回的字段刻意很少：界面要的只是"选哪个"。欢迎语要展示所以带上；
     * 权限配置、绑定的域列表这些不该出现在这里。
     */
    public List<UsableAssistant> listUsableAssistants(PermissionContext ctx) {
        List<UsableAssistant> out = new ArrayList<>();
        for (AssistantRow row : mapper.selectOnlineAssistants()) {
            Assistant a;
            try {
                // 与 forAssistant 走同一个加载器——列表与闸门必须看同一份策略
                a = loadAssistant(row);
            } catch (SemanticException e) {
                // **单个助理配置坏掉不能连累整条列表。**整体抛出的话，上层
                // （见 AskEngineStreamController 的 /assistants）会返回空数组，
                // 表现为"一个助理都没有"——用户完全不知道发生了什么，
                // 而真实原因只是某一个助理的 questions_json 或 perm_json 写坏了。
                // 这里跳过它并留日志；**管理员侧要能发现**，见 AssistantListView。
                log.warn("助理 id={} 配置有问题，已跳过：{}", row.id(), e.getMessage());
                continue;
            } catch (RuntimeException e) {
                // 策略来源是 IAM 时，这里会多一次服务调用。**按助理隔离**是必须的：
                // 不隔离的话一次 IAM 抖动会让所有助理在一个 200 响应里消失，
                // 界面显示"你没有助理"，而运维侧只有一条 warn
                log.error("助理 id={} 的策略读取失败，本轮跳过（权限服务可能不可用，"
                        + "**不是**该助理没有权限）：{}", row.id(), e.getMessage());
                continue;
            }
            if (!a.permission().grantsAccess(ctx)) {
                continue;
            }
            out.add(new UsableAssistant(a.id(), a.displayName(), a.avatar(),
                    a.welcome(), a.showSql(), a.hasMultipleDomains(), a.questions()));
        }
        return out;
    }

    /**
     * 一个可用的助理。
     *
     * @param hasMultipleDomains 是否绑定了多个域。前端据此决定要不要让用户选域——
     *                           **多域智能路由尚未实现**，不提示的话用户不知道
     *                           为什么同一个问题有时查这个域有时查那个
     * @param questions          推荐问题（FR-4.5）。问数页的空状态用它做引导——
     *                           此前它只存在库里与管理台，**从没下发到问数页**，
     *                           于是空状态只能写一句死板的示例，与当前助理无关
     */
    public record UsableAssistant(String id, String name, String avatar, String welcome,
                                  boolean showSql, boolean hasMultipleDomains,
                                  List<String> questions) {
        public UsableAssistant {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }
}
