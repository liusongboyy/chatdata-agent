package com.liusongboyy.chatdata.engine.nlsql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeRangeResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Test
    @DisplayName("「近三个月」按含本月在内的 3 个自然月解析")
    void recentThreeMonths() {
        TimeRangeResolver.ResolvedRange range = TimeRangeResolver.resolve("近三个月", null, null, TODAY);

        assertThat(range.startInclusive()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(range.endExclusive()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("「最近 3 个月」与中文数字等价")
    void recentThreeMonthsArabic() {
        TimeRangeResolver.ResolvedRange range = TimeRangeResolver.resolve("最近 3 个月", null, null, TODAY);

        assertThat(range.startInclusive()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(range.endExclusive()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("两位数的中文月份也能解析")
    void recentTwelveMonths() {
        TimeRangeResolver.ResolvedRange range = TimeRangeResolver.resolve("过去十二个月", null, null, TODAY);

        assertThat(range.startInclusive()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(range.endExclusive()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("无法解析的口径仍然必须报错")
    void unsupportedStillFails() {
        assertThatThrownBy(() -> TimeRangeResolver.resolve("近三个月半", null, null, TODAY))
                .isInstanceOf(TimeRangeResolver.TimeRangeException.class);
    }
}
