package com.liusongboyy.chatdata.engine.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评测集文件的读取与校验。
 *
 * <p>评测集是"验收基线"，写错了必须**当场报错**——静默用默认值跑出一份分数，
 * 比跑不起来更糟。
 */
class EvalSetLoaderTest {

    @TempDir
    Path dir;

    private static final String VALID = """
            {
              "name": "示例数据集",
              "domainId": "1372",
              "userId": "admin",
              "roles": ["管理员"],
              "cases": [
                {
                  "id": "c1",
                  "difficulty": "HARD",
                  "question": "问题",
                  "goldSql": "SELECT 1",
                  "columnOrder": "STRICT",
                  "tags": ["x"]
                }
              ]
            }
            """;

    @Test
    @DisplayName("解析单文件：枚举、默认值、可选字段都正确")
    void loadsSingleFile() throws Exception {
        Path f = dir.resolve("one.json");
        Files.writeString(f, VALID);

        EvalSet set = EvalSetLoader.load(f);

        assertThat(set.name()).isEqualTo("示例数据集");
        assertThat(set.domainId()).isEqualTo("1372");
        assertThat(set.userId()).isEqualTo("admin");
        assertThat(set.assistantId()).isNull();
        assertThat(set.cases()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("c1");
            assertThat(c.difficulty()).isEqualTo(EvalCase.Difficulty.HARD);
            assertThat(c.columnOrder()).isEqualTo(EvalCase.ColumnOrder.STRICT);
            assertThat(c.ordered()).isFalse();
        });
    }

    @Test
    @DisplayName("目录模式：加载全部 *.json，按文件名排序——多数据集扩展点")
    void loadsAllJsonInDirectory() throws Exception {
        Files.writeString(dir.resolve("b-domain.json"), VALID);
        Files.writeString(dir.resolve("a-domain.json"), VALID.replace("示例数据集", "另一个数据集"));
        Files.writeString(dir.resolve("notes.md"), "not an eval set");

        List<EvalSet> sets = EvalSetLoader.loadAll(dir);

        assertThat(sets).extracting(EvalSet::name)
                .containsExactly("另一个数据集", "示例数据集");
    }

    @Test
    @DisplayName("重复用例 id 直接拒绝——否则报告里的失败定位会指错")
    void rejectsDuplicateIds() throws Exception {
        Path f = dir.resolve("dup.json");
        Files.writeString(f, """
                {
                  "domainId": "1",
                  "roles": ["分析师"],
                  "cases": [
                    {"id": "same", "question": "q1", "goldSql": "SELECT 1"},
                    {"id": "same", "question": "q2", "goldSql": "SELECT 2"}
                  ]
                }
                """);

        assertThatThrownBy(() -> EvalSetLoader.load(f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复用例 id");
    }

    @Test
    @DisplayName("未知字段直接报错——拼错 columOrder 不能静默按默认值跑")
    void rejectsUnknownField() throws Exception {
        Path f = dir.resolve("typo.json");
        Files.writeString(f, VALID.replace("\"columnOrder\"", "\"columOrder\""));

        assertThatThrownBy(() -> EvalSetLoader.load(f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columOrder");
    }

    @Test
    @DisplayName("roles 为空直接拒绝——否则每条都会被权限判成 DENIED")
    void rejectsEmptyRoles() throws Exception {
        Path f = dir.resolve("noroles.json");
        Files.writeString(f, """
                {"domainId": "1", "roles": [],
                 "cases": [{"id": "c1", "question": "q", "goldSql": "SELECT 1"}]}
                """);

        assertThatThrownBy(() -> EvalSetLoader.load(f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roles");
    }
}
