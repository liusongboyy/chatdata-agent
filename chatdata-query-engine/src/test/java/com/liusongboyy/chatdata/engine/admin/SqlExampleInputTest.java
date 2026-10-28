package com.liusongboyy.chatdata.engine.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 范例保存时的校验。
 *
 * <p>范例会**原样进 prompt**。一条 {@code DELETE} 存进去，下一个相似问题就可能被
 * 带成删除语句——所以这不是"输入格式检查"，是防止范例库自己变成注入源。
 */
class SqlExampleInputTest {

    @Test
    @DisplayName("★ 写操作一律拒绝 —— 范例库会原样进 prompt")
    void rejectsWriteStatements() {
        assertThatThrownBy(() -> new SqlExampleInput("删掉保单", "DELETE FROM ins_policy", null)
                .validated())
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("只允许 SELECT");

        assertThatThrownBy(() -> new SqlExampleInput("清空", "TRUNCATE ins_policy", null).validated())
                .isInstanceOf(AdminException.class);
    }

    @Test
    @DisplayName("多语句也拒绝")
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> new SqlExampleInput(
                "q", "SELECT 1; DROP TABLE ins_policy", null).validated())
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("多语句");
    }

    @Test
    @DisplayName("问法或 SQL 为空时拒绝 —— 范例靠问法被检索到")
    void rejectsBlank() {
        assertThatThrownBy(() -> new SqlExampleInput("  ", "SELECT 1", null).validated())
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("示例问法");
        assertThatThrownBy(() -> new SqlExampleInput("q", " ", null).validated())
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("SQL 不能为空");
    }

    @Test
    @DisplayName("合法 SELECT 通过，且默认启用")
    void acceptsSelect() {
        SqlExampleInput v = new SqlExampleInput(" 各区域保费 ", " SELECT 1 ", null).validated();

        assertThat(v.question()).isEqualTo("各区域保费");
        assertThat(v.sql()).isEqualTo("SELECT 1");
        assertThat(v.enabled()).isTrue();
        assertThat(v.note()).isNull();
    }
}
