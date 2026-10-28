package com.liusongboyy.chatdata.engine.audit;

import com.liusongboyy.chatdata.engine.audit.mapper.EngineAuditMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 审计记录器的测试。
 *
 * <p>重点是**失败隔离**：审计写不进去不能让问数失败。
 * 这条契约容易在重构时被无意破坏——加个 try/catch 很自然，
 * 但"顺手向上抛一下"同样很自然。
 */
class DbAskAuditRecorderTest {

    private static AskAuditRecord record() {
        return new AskAuditRecord("u1", "分析师", "上个月的保费",
                null, "d1", "{}", "SELECT 1", 1, 100L,
                AskAuditOutcome.SUCCESS, null, LocalDateTime.now());
    }

    /** 写就抛异常的 mapper。 */
    private static EngineAuditMapper explodingMapper() {
        return record -> {
            throw new RuntimeException("表不存在");
        };
    }

    /** 正常写入并记录下来的 mapper。 */
    private static class CapturingMapper implements EngineAuditMapper {
        AskAuditRecord captured;

        @Override
        public int insert(AskAuditRecord record) {
            captured = record;
            return 1;
        }
    }

    @Test
    @DisplayName("正常写入")
    void writes() {
        CapturingMapper mapper = new CapturingMapper();

        new DbAskAuditRecorder(mapper).record(record());

        assertThat(mapper.captured).isNotNull();
        assertThat(mapper.captured.username()).isEqualTo("u1");
    }

    @Test
    @DisplayName("★ 写失败时吞掉异常，不向上抛 —— 审计写不进去不该让问数失败")
    void swallowsFailure() {
        assertThatCode(() -> new DbAskAuditRecorder(explodingMapper()).record(record()))
                .as("用户问到了数据，却因为审计写失败拿不到结果，这个取舍是错的")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 超长的错误信息被截断 —— 数据库列是 VARCHAR(512)")
    void truncatesLongError() {
        String huge = "x".repeat(2000);

        AskAuditRecord rec = new AskAuditRecord("u1", null, "q", null, "d1", null, null,
                null, 1L, AskAuditOutcome.FAILURE, huge, LocalDateTime.now());

        assertThat(rec.errorMessage().length())
                .as("不截断会让 insert 直接失败，于是这条审计彻底丢失")
                .isLessThanOrEqualTo(501);
        assertThat(rec.errorMessage()).endsWith("…");
    }

    @Test
    @DisplayName("缺 username 时构造失败 —— 没有身份就无从审计")
    void requiresUsername() {
        assertThatCode(record()::username).doesNotThrowAnyException();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new AskAuditRecord(null, null, "q", null, null, null, null,
                                null, 1L, AskAuditOutcome.SUCCESS, null, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("NOOP 实现什么都不做")
    void noopDoesNothing() {
        assertThatCode(() -> AskAuditRecorder.NOOP.record(record())).doesNotThrowAnyException();
    }
}
