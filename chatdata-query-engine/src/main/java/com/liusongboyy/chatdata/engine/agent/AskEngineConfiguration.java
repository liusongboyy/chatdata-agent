package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.audit.AskAuditRecorder;
import com.liusongboyy.chatdata.engine.audit.DbAskAuditRecorder;
import com.liusongboyy.chatdata.engine.admin.AdminAssistantService;
import com.liusongboyy.chatdata.engine.admin.AdminDatasourceService;
import com.liusongboyy.chatdata.engine.admin.AdminDomainService;
import com.liusongboyy.chatdata.engine.admin.mapper.SemanticAdminMapper;
import com.liusongboyy.chatdata.engine.audit.mapper.EngineAuditMapper;
import com.liusongboyy.chatdata.engine.datasource.DatasourceRegistry;
import com.liusongboyy.chatdata.engine.datasource.MetaReader;
import com.liusongboyy.chatdata.engine.datasource.PasswordCipher;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.raw.RawSchemaBuilder;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;

/**
 * 引擎的 Bean 装配。
 *
 * <p>只装配**无状态、可共享**的组件。{@code AskService} 不在其中——
 * 它绑定一个具体的语义域，而域是按请求路由的，做成单例就得在每次调用时
 * 重新判断"这次用哪个域"，那等于把路由逻辑塞进服务内部。
 */
@Configuration
public class AskEngineConfiguration {

    /**
     * LLM 模型。
     *
     * <p><strong>{@code @Lazy} 是刻意的。</strong>它需要 api-key，而开发环境
     * 常常没有。做成急加载会让"没配 key"变成"应用起不来"——那太粗暴了，
     * 因为大部分功能（管理面、语义建模）根本不需要模型。
     *
     * <p>惰性加载后：应用照常启动，真去问数时才报"缺少 api-key"，
     * 且错误信息直接点名是哪个配置项。
     */
    @Bean
    @Lazy
    public Model askEngineModel(AskEngineModelProperties props) {
        return AskEngineModelFactory.build(props);
    }

    /**
     * 多轮追问的会话状态存储。
     *
     * <p><strong>惰性，和模型同样的理由，但这里还有一条更要紧的。</strong>
     * 建这个 bean 会去连库、检查表和库是否存在，必要时还要建表——
     * 那是启动期的**副作用**。惰性之后：没配好权限或表没建的环境，
     * 照样能启动、能进管理台，只有真去问数时才报错，且报的是与这件事直接相关的错。
     */
    @Bean
    @Lazy
    public AgentStateStore askSessionStore(DataSource dataSource, AskSessionProperties props) {
        return AskSessionStores.create(dataSource, props);
    }

    /**
     * DSL 生成器。依赖模型，故同样是惰性的。
     *
     * <p>用 {@link ObjectProvider} 而不是直接注入 {@code AgentStateStore}：
     * 关掉会话持久化时（{@code chatdata.engine.session.enabled=false}）
     * 这个 bean 根本不该被创建——直接注入会把它连同建表检查一起拉起来，
     * 那"关掉"就成了关掉一半。
     */
    @Bean
    @Lazy
    public DslSource dslSource(Model askEngineModel,
                              ObjectProvider<AgentStateStore> askSessionStore,
                              AskSessionProperties sessionProps) {
        AgentStateStore store = sessionProps.isEnabled() ? askSessionStore.getObject() : null;
        return new DslGenerator(askEngineModel, store, sessionProps.getConflictPolicy());
    }

    /**
     * 数据源密码的加解密。
     *
     * <p>密钥缺失时**启动即失败**（不像模型那样惰性）——因为一个用默认密钥加密的
     * 密码库等于没有加密，这个不能"等用到再说"。
     */
    @Bean
    public PasswordCipher enginePasswordCipher(
            @Value("${chatdata.engine.datasource.secret:}") String secret) {
        return new PasswordCipher(secret);
    }

    @Bean(destroyMethod = "close")
    public DatasourceRegistry engineDatasourceRegistry(SemanticConfigMapper mapper,
                                                       PasswordCipher enginePasswordCipher) {
        return new DatasourceRegistry(mapper, enginePasswordCipher);
    }

    @Bean
    public SqlExecutor engineSqlExecutor(DatasourceRegistry engineDatasourceRegistry) {
        return new SqlExecutor(engineDatasourceRegistry);
    }

    /**
     * 直连模式的"说明书"生成器：表结构 + 采样值，带 10 分钟缓存。
     *
     * <p>它是无状态的（缓存挂在实例里，进程内共享），可以单例。
     */
    @Bean
    public RawSchemaBuilder engineRawSchemaBuilder(DatasourceRegistry engineDatasourceRegistry) {
        return new RawSchemaBuilder(engineDatasourceRegistry);
    }

    @Bean
    public AskAuditRecorder engineAuditRecorder(EngineAuditMapper mapper) {
        return new DbAskAuditRecorder(mapper);
    }

    /** 数据源管理。密码走 {@link PasswordCipher}，普通更新不碰密码。 */
    @Bean
    public AdminDatasourceService adminDatasourceService(SemanticConfigMapper readMapper,
                                                         SemanticAdminMapper writeMapper,
                                                         PasswordCipher enginePasswordCipher) {
        return new AdminDatasourceService(readMapper, writeMapper, enginePasswordCipher);
    }

    /**
     * 语义域与注册表管理。
     *
     * <p>数据策略归本模块所有（ADR-0010），管理台是唯一写入口，因此这里不再需要
     * 与外部策略来源比对写入内容。
     */
    @Bean
    public AdminDomainService adminDomainService(SemanticConfigMapper readMapper,
                                                 SemanticAdminMapper writeMapper,
                                                 MetaReader engineMetaReader) {
        return new AdminDomainService(readMapper, writeMapper, engineMetaReader);
    }

    /** 元数据读取。注册表的"从数据源导入"与列名校验都靠它。 */
    @Bean
    public MetaReader engineMetaReader(DatasourceRegistry engineDatasourceRegistry) {
        return new MetaReader(engineDatasourceRegistry);
    }

    /**
     * 从数据源生成域草稿。
     *
     * <p>这个 bean 存在的理由：建模覆盖度是准确率最大的杠杆（保险域补三条聚合登记
     * 就从 74.2% 升到 83.3%，代码一行未改），但手工建模极贵——AdventureWorks 28 表
     * 花了大半天。<b>最大的杠杆挂着最高的使用成本</b>，这个服务就是为了削掉那部分成本。
     *
     * <p>它<b>不写库</b>：只产出草稿，管理员确认后才走现有写入接口。
     */
    @Bean
    public com.liusongboyy.chatdata.engine.modeling.ModelingDraftService modelingDraftService(
            MetaReader engineMetaReader, DatasourceRegistry engineDatasourceRegistry) {
        return new com.liusongboyy.chatdata.engine.modeling.ModelingDraftService(
                engineMetaReader, engineDatasourceRegistry);
    }

    /**
     * 列画像：给已注册的列生成描述与取值样例。
     *
     * <p>画像结果进 prompt，是提升准确率最高性价比的一环（BIRD 实测 +7.3 个百分点）。
     * 它需要连数据源，故依赖 {@link SqlExecutor}。
     */
    @Bean
    public com.liusongboyy.chatdata.engine.admin.AdminColumnProfileService
            adminColumnProfileService(AdminDomainService adminDomainService,
                                     MetaReader engineMetaReader,
                                     DatasourceRegistry engineDatasourceRegistry,
                                     SqlExecutor sqlExecutor) {
        return new com.liusongboyy.chatdata.engine.admin.AdminColumnProfileService(
                adminDomainService, engineMetaReader, engineDatasourceRegistry,
                new com.liusongboyy.chatdata.engine.semantic.ColumnProfiler(sqlExecutor));
    }

    /** SQL 范例库维护。 */
    @Bean
    public com.liusongboyy.chatdata.engine.admin.AdminSqlExampleService adminSqlExampleService(
            SemanticAdminMapper writeMapper) {
        return new com.liusongboyy.chatdata.engine.admin.AdminSqlExampleService(writeMapper);
    }

    /** 助理管理。数据策略与域一样归本模块所有，见 {@link #adminDomainService}。 */
    @Bean
    public AdminAssistantService adminAssistantService(SemanticConfigMapper readMapper,
                                                       SemanticAdminMapper writeMapper) {
        return new AdminAssistantService(readMapper, writeMapper);
    }

    /**
     * 反馈闭环。
     *
     * <p>它跨了三块：反馈表（{@code FeedbackMapper}）、域的授权边界
     * （{@code SemanticConfigMapper} 里的注册表）、范例写入
     * （{@code AdminSqlExampleService}）。所以只能在装配层拼，不能塞进其中任何一个服务。
     */
    @Bean
    public com.liusongboyy.chatdata.engine.feedback.FeedbackService engineFeedbackService(
            com.liusongboyy.chatdata.engine.feedback.mapper.FeedbackMapper feedbackMapper,
            SemanticConfigMapper readMapper,
            com.liusongboyy.chatdata.engine.admin.AdminSqlExampleService sqlExampleService) {
        return new com.liusongboyy.chatdata.engine.feedback.FeedbackService(
                feedbackMapper, readMapper, sqlExampleService);
    }
}
