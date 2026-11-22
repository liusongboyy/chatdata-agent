package com.liusongboyy.chatdata.ask.permission;

import com.liusongboyy.iam.client.IamPermissionReporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动时的权限上报。
 *
 * <h2>最重要的断言是"失败不抛"</h2>
 *
 * <p>上报是"让管理台知道有哪些权限"，**不是运行时的依赖**：权限判定读的
 * 是 IAM 库里的数据，而那份数据上一次上报（或迁移脚本）已经灌好了。
 *
 * <p>拦启动的后果很具体：一次 IAM 抖动 → 所有 ask 实例起不来 →
 * 用户问数全挂，而真实原因只是"权限清单没同步"。
 */
class PermissionReporterTest {

    private static ObjectProvider<IamPermissionReporter> providerOf(IamPermissionReporter client) {
        @SuppressWarnings("unchecked")
        ObjectProvider<IamPermissionReporter> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(client);
        return p;
    }

    @Test
    @DisplayName("★ IAM 不可用 → 不抛（否则一次抖动会让整个问数起不来）")
    void doesNotThrowWhenIamUnavailable() {
        IamPermissionReporter client = mock(IamPermissionReporter.class);
        when(client.report(anyString(), any()))
                .thenThrow(new IllegalStateException("权限上报失败：Connection refused"));

        var reporter = new PermissionReporter(providerOf(client), "1.4.0");

        assertThatCode(() -> reporter.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ IAM 拒绝（比如前缀不对）→ 也不抛")
    void doesNotThrowWhenIamRejects() {
        IamPermissionReporter client = mock(IamPermissionReporter.class);
        when(client.report(anyString(), any()))
                .thenThrow(new IllegalStateException(
                        "IAM 拒绝了权限上报（HTTP 400）：权限码必须以「ask.」开头"));

        var reporter = new PermissionReporter(providerOf(client), "1.4.0");

        assertThatCode(() -> reporter.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 没配密钥（客户端为 null）→ 跳过，不报错")
    void skipsWhenClientAbsent() {
        var reporter = new PermissionReporter(providerOf(null), "1.4.0");

        assertThatCode(() -> reporter.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("正常上报：把清单原样传给客户端")
    void reportsFullCatalog() {
        IamPermissionReporter client = mock(IamPermissionReporter.class);
        when(client.report(anyString(), any()))
                .thenReturn(new IamPermissionReporter.Result(0, 0, 21));

        var reporter = new PermissionReporter(providerOf(client), "1.4.0");
        reporter.run(null);

        var items = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client).report(org.mockito.ArgumentMatchers.eq("1.4.0"), items.capture());
        assertThat(items.getValue()).hasSize(AskPermissionCatalog.ALL.size());
    }
}
