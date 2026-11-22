package com.liusongboyy.chatdata.ask.permission;

import com.liusongboyy.iam.client.IamPermissionReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把问数的权限清单上报给 IAM。
 *
 * <h2>失败绝不拦启动</h2>
 *
 * <p>上报是"让管理台知道有哪些权限"，**不是运行时的依赖**：
 * 权限判定读的是 IAM 库里的 {@code iam_permission}，而那份数据
 * 上一次上报（或迁移脚本）已经灌好了。这次上报失败不影响任何已有权限。
 *
 * <p>拦启动的后果很具体：一次 IAM 抖动 → 所有 ask 实例起不来 →
 * 用户问数全挂。**而真实原因只是"权限清单没同步"**，那个问题
 * 几分钟后会因为下一次重启自愈。
 *
 * <p>阶段 0 踩过一模一样的坑：channel 读角色目录没设超时，
 * {@code ApplicationRunner} 永久阻塞，readiness 永远 {@code OUT_OF_SERVICE}。
 * 所以这里除了 try/catch，{@link IamPermissionReporter} 里还显式设了超时。
 *
 * <h2>为什么不阻塞等结果</h2>
 *
 * <p>{@code ApplicationRunner} 是同步的，上报要几百毫秒。但那是在启动路径上，
 * 一次 HTTP 往返换来"启动日志里能看到上报结果"，值得。
 * 改成异步的话，"上报失败"会变成一条在执行完启动之后才出现的日志，
 * 与其它启动日志的时序对不上，排查时容易误判。
 */
@Component
public class PermissionReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionReporter.class);

    /**
     * 上报用的客户端。
     *
     * <p>用 {@code ObjectProvider}：密钥没配时客户端就不存在，
     * 而"没配"不该让应用起不来——本地只问数、不连 IAM 的场景是正常的。
     */
    private final ObjectProvider<IamPermissionReporter> reporter;

    /** 应用版本。从构建信息里读不到就写 unknown，只用于排障。 */
    private final String appVersion;

    public PermissionReporter(ObjectProvider<IamPermissionReporter> reporter,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${chatdata.version:unknown}") String appVersion) {
        this.reporter = reporter;
        this.appVersion = appVersion;
    }

    @Override
    public void run(ApplicationArguments args) {
        IamPermissionReporter client = reporter.getIfAvailable();
        if (client == null) {
            log.info("未配置权限上报客户端（缺 chatdata.iam.client-secret），跳过权限上报");
            return;
        }

        List<IamPermissionReporter.Item> items = AskPermissionCatalog.ALL.stream()
                .map(i -> new IamPermissionReporter.Item(
                        i.code(), i.label(), i.kind().name(), i.parent(), i.sensitive()))
                .toList();

        try {
            IamPermissionReporter.Result r = client.report(appVersion, items);
            if (r.pendingAdded() > 0) {
                // 有待审核的新码：**说清楚要去哪处理**，而不是只报个数字
                log.warn("权限上报完成：{} 个新权限码待审核，请在 IAM 管理台的「权限审核」页确认",
                        r.pendingAdded());
            } else {
                log.info("权限上报完成：{} 个权限码，{} 个有变更",
                        items.size(), r.updated());
            }
        } catch (RuntimeException e) {
            // **不抛。** 见类注释：上报失败不影响已有权限，
            // 而拦启动会把"权限清单没同步"放大成"整个问数不可用"
            log.warn("权限上报失败，本次跳过（不影响已有权限）：{}", e.getMessage());
        }
    }
}
