package com.liusongboyy.chatdata.engine.model;

import com.liusongboyy.chatdata.engine.model.ModelAccess.Denial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型访问控制。
 *
 * <p>这是整个功能里<b>最容易出错、错了最危险</b>的一块：
 * 判错方向会让没权限的人用上贵模型，或者让有权限的人一个都选不了。
 */
class ModelAccessTest {

    private static ModelDef model(String key, boolean isDefault, List<String> roles) {
        return new ModelDef(key, key, null, "https://x/v1", key, roles,
                BigDecimal.ONE, null, BigDecimal.ONE, null, isDefault, true);
    }

    private static final List<ModelDef> ALL = List.of(
            model("fast", true, List.of()),                 // 默认，所有人可用
            model("strong", false, List.of("manager", "admin")),
            model("gpt", false, List.of("admin")));

    // ── 可选列表 ──

    @Test
    @DisplayName("★ 普通员工只看到不限制的那个")
    void normalUserSeesOnlyOpenModel() {
        var list = ModelAccess.selectable(ALL, Set.of("employee"), null);

        assertThat(list).extracting(ModelDef::key).containsExactly("fast");
    }

    @Test
    @DisplayName("★ 管理员看到全部")
    void adminSeesAll() {
        var list = ModelAccess.selectable(ALL, Set.of("admin"), null);

        assertThat(list).extracting(ModelDef::key)
                .containsExactlyInAnyOrder("fast", "strong", "gpt");
    }

    @Test
    @DisplayName("★ 空白名单 = 不限制，不是谁都不可用")
    void emptyRoleListMeansUnrestricted() {
        // 反过来（空=谁都不可用）会让新加的模型默认隐身，
        // 管理员配完以为没生效——这是最容易搞反的一处
        assertThat(ModelAccess.rolesAllow(model("x", false, List.of()), Set.of("anyone")))
                .isTrue();
    }

    @Test
    @DisplayName("域绑定时只列出那一个")
    void domainBindingFiltersList() {
        var list = ModelAccess.selectable(ALL, Set.of("admin"), "strong");

        assertThat(list).extracting(ModelDef::key).containsExactly("strong");
    }

    @Test
    @DisplayName("停用的模型不出现在列表里")
    void disabledModelHidden() {
        ModelDef disabled = new ModelDef("old", "old", null, "u", "m", List.of(),
                null, null, null, null, false, false);

        assertThat(ModelAccess.selectable(List.of(disabled), Set.of("admin"), null)).isEmpty();
    }

    // ── 解析 ──

    @Test
    @DisplayName("★ 用户没选、域没绑 → 用默认")
    void fallsBackToDefault() {
        var r = ModelAccess.resolve(ALL, Set.of("employee"), null, null);

        assertThat(r.ok()).isTrue();
        assertThat(r.model().key()).isEqualTo("fast");
    }

    @Test
    @DisplayName("★ 越权选择被拒——不静默降级成默认")
    void outOfRoleRejectedNotDowngraded() {
        // 降级是最危险的：用户以为在用强模型、实际用了弱模型，
        // 会让他对结果产生错误判断
        var r = ModelAccess.resolve(ALL, Set.of("employee"), null, "strong");

        assertThat(r.ok()).isFalse();
        assertThat(r.denial().code()).isEqualTo("MODEL_NOT_ALLOWED");
        assertThat(r.denial().message()).contains("无权");
    }

    @Test
    @DisplayName("★ 域绑定优先于用户选择")
    void domainBindingBeatsUserChoice() {
        var r = ModelAccess.resolve(ALL, Set.of("admin"), "fast", "gpt");

        assertThat(r.ok()).isFalse();
        assertThat(r.denial().code()).isEqualTo("MODEL_LOCKED_BY_DOMAIN");
    }

    @Test
    @DisplayName("★ 域绑定不再校验角色白名单")
    void domainBindingSkipsRoleCheck() {
        // 域绑定是技术约束（数据量大、弱模型跑不动），不是权限授予。
        // 若这里再卡角色，会出现「域锁定的模型这个角色没权限 → 他连这个域都用不了」，
        // 而管理员的本意是"这个域大家都得用强模型"
        var r = ModelAccess.resolve(ALL, Set.of("employee"), "strong", null);

        assertThat(r.ok()).as("域锁定的模型，员工也该能用").isTrue();
        assertThat(r.model().key()).isEqualTo("strong");
    }

    @Test
    @DisplayName("★ 绑定指向的模型没了 → 明确报错，不悄悄回落默认")
    void missingBoundModelIsError() {
        var r = ModelAccess.resolve(ALL, Set.of("admin"), "deleted-model", null);

        assertThat(r.ok()).isFalse();
        assertThat(r.denial().code()).isEqualTo("BOUND_MODEL_UNAVAILABLE");
        // 回落的后果：这个域突然用上弱模型，而没人知道
    }

    @Test
    @DisplayName("没有默认模型时报错而不是随便挑一个")
    void noDefaultIsError() {
        var all = List.of(model("a", false, List.of()), model("b", false, List.of()));

        var r = ModelAccess.resolve(all, Set.of("admin"), null, null);

        assertThat(r.ok()).isFalse();
        assertThat(r.denial().code()).isEqualTo("NO_DEFAULT_MODEL");
    }

    @Test
    @DisplayName("默认模型也要过角色：某些角色连默认都不该用")
    void defaultStillChecksRole() {
        var all = List.of(model("restricted", true, List.of("admin")));

        var r = ModelAccess.resolve(all, Set.of("auditor"), null, null);

        assertThat(r.ok()).isFalse();
        assertThat(r.denial().code()).isEqualTo("NO_MODEL_FOR_ROLE");
    }

    @Test
    @DisplayName("请求不存在的模型 → 明确说「不存在」，不是「无权限」")
    void unknownModelVsForbidden() {
        var r = ModelAccess.resolve(ALL, Set.of("admin"), null, "nope");

        assertThat(r.denial().code()).isEqualTo("MODEL_NOT_FOUND");
        assertThat(r.denial().message()).contains("不存在");
    }

    @Test
    @DisplayName("Result 必须且只能有模型或拒绝原因")
    void resultIsExclusive() {
        assertThatThrownBy(() -> new ModelAccess.Result(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelAccess.Result(
                model("x", false, List.of()), new Denial("E", "m")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
