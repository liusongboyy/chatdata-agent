package com.liusongboyy.chatdata.ask.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康度页面的「数据量大」提示。
 *
 * <p>这个提示存在的理由：页面是实时全量扫描的，数据量涨上来会慢。
 * 而<b>没有人会主动怀疑一个健康度页面本身有问题</b>——不给提示的话，
 * 用户只会看到转圈然后以为页面坏了。
 */
class HealthServiceNoticeTest {

    /** slowNotice 是私有的，用反射测——为它公开一个方法不值得。 */
    private static String slowNotice(long rows, int window) throws Exception {
        Method m = HealthService.class.getDeclaredMethod(
                "slowNotice", long.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, rows, window);
    }

    @Test
    @DisplayName("★ 数据量小时不提示——正常情况不该有噪音")
    void noNoticeUnderThreshold() throws Exception {
        assertThat(slowNotice(0, 7)).isNull();
        assertThat(slowNotice(123, 7)).isNull();
        assertThat(slowNotice(49_999, 7)).isNull();
    }

    @Test
    @DisplayName("★ 超过阈值时提示，且说清「为什么慢」")
    void noticeOverThreshold() throws Exception {
        String n = slowNotice(50_000, 7);

        assertThat(n).isNotNull();
        assertThat(n).as("要说明是实时统计导致的").contains("实时");
        assertThat(n).as("要给出实际的量").contains("50000");
    }

    @Test
    @DisplayName("★ 提示要给可执行的下一步，不只是报告慢")
    void noticeIsActionable() throws Exception {
        String n = slowNotice(100_000, 30);

        // 只说"数据量大"等于把问题丢回去
        assertThat(n).as("给出立刻能做的缓解手段").contains("时间范围调小");
        assertThat(n).as("说明长期该做什么").contains("预聚合");
    }

    @Test
    @DisplayName("返回 null 而不是空串——前端用 v-if 判，空串会渲染成空白块")
    void returnsNullNotBlank() throws Exception {
        assertThat(slowNotice(10, 7)).isNull();
    }

    @Test
    @DisplayName("阈值边界：49999 不提示，50000 提示")
    void thresholdBoundary() throws Exception {
        assertThat(slowNotice(49_999, 7)).isNull();
        assertThat(slowNotice(50_000, 7)).isNotNull();
    }
}
