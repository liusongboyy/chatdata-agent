package com.liusongboyy.chatdata.ask.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.chat.AskServiceFactory;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.eval.EvalReport;
import com.liusongboyy.chatdata.engine.eval.EvalRunner;
import com.liusongboyy.chatdata.engine.eval.EvalSet;
import com.liusongboyy.chatdata.engine.eval.EvalSetLoader;
import com.liusongboyy.chatdata.engine.eval.EvalSuiteReport;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测入口：{@code --spring.profiles.active=eval} 时启动、跑完即退出。
 *
 * <h2>为什么走 CLI 而不是 HTTP 接口</h2>
 *
 * <p>评测是**构建期质量门禁**，不是产品功能。走 HTTP 就要处理登录、令牌、SSE 分帧，
 * 这些传输层问题与"问数准不准"无关，却足以让 CI 挂掉。
 * 这里直接调服务层，与控制器共用同一个 {@link AskServiceFactory}。
 *
 * <h2>为什么用 Spring profile 而不是独立模块</h2>
 *
 * <p>要跑真实问数链路，就需要模型、数据源、语义配置的完整装配。另起一个模块
 * 就得把这些 Bean 再拼一遍——两套装配迟早会不一致，那时评测分数与线上行为脱节，
 * 评测本身也就失去意义。profile 只是控制"启动后做什么"，不改变装配。
 */
@Component
@Profile("eval")
public class EvalCommandLineRunner implements CommandLineRunner {

    private static final ObjectMapper M = new ObjectMapper();

    private final AskServiceFactory factory;
    private final SqlExecutor sqlExecutor;
    private final EvalProperties props;
    private final ApplicationContext context;

    public EvalCommandLineRunner(AskServiceFactory factory, SqlExecutor sqlExecutor,
                                 EvalProperties props, ApplicationContext context) {
        this.factory = factory;
        this.sqlExecutor = sqlExecutor;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        int code;
        try {
            List<EvalSet> sets = EvalSetLoader.loadAll(Path.of(props.getPath()));
            EvalRunner runner = new EvalRunner(factory, sqlExecutor, factory::datasourceIdOf);

            List<EvalReport> reports = new ArrayList<>(sets.size());
            for (EvalSet set : sets) {
                System.out.println(">>> 数据集：" + set.name()
                        + "（域 " + set.domainId() + "，" + set.cases().size() + " 条）");
                reports.add(runner.run(set.limitedTo(props.getLimit()), System.out::println));
            }

            EvalSuiteReport suite = EvalSuiteReport.of(reports);
            writeReport(suite);
            System.out.println(suite.summary());

            code = suite.passRate() >= props.getMinPassRate() ? 0 : 1;
            if (code != 0) {
                System.out.printf("通过率 %.1f%% 低于阈值 %.1f%%%n",
                        suite.passRate() * 100, props.getMinPassRate() * 100);
            }
        } catch (Exception e) {
            // 评测跑不起来不是"0 分"，要让人一眼看出是环境/配置问题
            System.err.println("评测执行失败：" + e.getMessage());
            e.printStackTrace(System.err);
            code = 2;
        }

        // 先关 Spring 上下文（释放连接池、线程池），再退出。
        // 不退出的话，即使 CLI 已经跑完，Tomcat 仍会占着端口，CI 会一直挂着
        int exitCode = code;
        int exit = SpringApplication.exit(context, () -> exitCode);
        System.exit(exit);
    }

    private void writeReport(EvalSuiteReport suite) {
        try {
            Path out = Path.of(props.getReport());
            if (out.toAbsolutePath().getParent() != null) {
                Files.createDirectories(out.toAbsolutePath().getParent());
            }
            M.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), suite);
            System.out.println("报告已写入：" + out.toAbsolutePath());
        } catch (Exception e) {
            // 报告写不出去不该让评测结果消失：终端摘要仍然会打印
            System.err.println("写评测报告失败：" + e.getMessage());
        }
    }
}
