package com.liusongboyy.chatdata.iam.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内省缓存的编解码。
 *
 * <p>这段代码在滚动发布时最容易出错：**新字段只能追加在末尾**，
 * 否则旧实例读到的第 n 段含义就变了，而错读的后果是"用别人的组织范围去查数"。
 * 下面的用例把这条约定钉住。
 */
class IamIntrospectorCodecTest {

    private static IamPrincipal sample(String orgCodePath, List<String> orgCodePaths) {
        return new IamPrincipal(
                7L, "t_default", "zhangsan", "张三",
                110L, "SUB-EAST", "/1/100/110/", List.of("/1/100/110/", "/1/12/"),
                orgCodePath, orgCodePaths,
                Set.of("区域经理"), Set.of("ask.admin.domains"), Set.of("ask.read"),
                "ask-resource-server", Instant.parse("2026-10-28T10:00:00Z"), 3);
    }

    @Test
    @DisplayName("★ 编码路径经缓存往返后不丢、不乱序")
    void codePathsSurviveRoundTrip() {
        IamPrincipal original = sample("/HQ/HQ-CVS/SUB-EAST/",
                List.of("/HQ/HQ-CVS/SUB-EAST/", "/HQ/华南/"));

        IamPrincipal back = IamIntrospector.decode(IamIntrospector.encode(original));

        assertThat(back.orgCodePath()).isEqualTo("/HQ/HQ-CVS/SUB-EAST/");
        // 顺序有语义（主组织在前），必须按原序返回
        assertThat(back.orgCodePaths()).containsExactly("/HQ/HQ-CVS/SUB-EAST/", "/HQ/华南/");
        // 原有的 id 路径不能因为新字段而被挤错位
        assertThat(back.orgPath()).isEqualTo("/1/100/110/");
        assertThat(back.orgPaths()).containsExactly("/1/100/110/", "/1/12/");
        assertThat(back.username()).isEqualTo("zhangsan");
        assertThat(back.roles()).containsExactly("区域经理");
    }

    @Test
    @DisplayName("编码路径为空时往返仍成立（未绑定组织的历史用户）")
    void nullCodePathRoundTrips() {
        IamPrincipal back = IamIntrospector.decode(IamIntrospector.encode(sample(null, List.of())));

        assertThat(back.orgCodePath()).isNull();
        assertThat(back.orgCodePaths()).isEmpty();
    }

    @Test
    @DisplayName("★ 旧格式缓存（少 2 段）抛异常 → 回退问 IAM，不读出错误身份")
    void legacyCacheIsRejected() {
        // 14 段 = 加入编码路径之前的格式
        String legacy = "7\u001ft_default\u001fzhangsan\u001f张三\u001f110\u001fSUB-EAST\u001f/1/100/110/"
                + "\u001f区域经理\u001fask.read\u001fask-resource-server\u001f2026-10-28T10:00:00Z\u001f3"
                + "\u001fask.admin.domains\u001f/1/100/110/";

        assertThatThrownBy(() -> IamIntrospector.decode(legacy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("字段数不足");
    }
}
