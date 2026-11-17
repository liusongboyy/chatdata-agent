package com.liusongboyy.chatdata.channel.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道不碰 IAM 的库。
 *
 * <h2>为什么用源码扫描而不是行为测试</h2>
 *
 * <p>这个耦合的回归方式很隐蔽：有人在 {@code ChannelIdentityService} 里加一句
 * {@code jdbc.query("SELECT ... FROM chatdata_iam.iam_user")}，<b>所有测试都会通过</b>——
 * 因为它能跑、能返回正确结果。问题要到 IAM 拆库那天才爆出来，而那时
 * 失败表现是「飞书绑定查不到」，与「IAM 表结构变了」看起来毫无关系。
 *
 * <p>行为测试抓不到它（行为没变），只能查源码。
 *
 * <h2>查什么</h2>
 *
 * <p>{@code chatdata-channel} 的 main 源码里不能出现：
 *
 * <ul>
 *   <li>{@code chatdata_iam} 这个库名</li>
 *   <li>{@code iam_user} 这张表名</li>
 * </ul>
 *
 * <p>两者都是 IAM 的内部实现。渠道要知道「这个 open_id 是谁」，
 * 走 {@code /iam/identity/resolve}。
 *
 * <h2>只查代码，不查注释</h2>
 *
 * <p>注释里写「它原来查 {@code chatdata_iam.iam_user}，现在改成调接口」是<b>应该保留</b>的
 * 历史说明——后来的人需要知道为什么是接口而不是一句 SQL。
 * 所以扫描前先剥掉注释，否则要么误报、要么逼着人删掉那段说明。
 */
class ChannelDoesNotTouchIamDatabaseTest {

    /** 会暴露 IAM schema 的字符串。 */
    private static final String[] FORBIDDEN = {
            "chatdata_iam",
            "iam_user",
    };

    @Test
    @DisplayName("★ channel 源码里没有 IAM 的库名与表名")
    void noIamSchemaReferences() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(Files.isDirectory(root))
                .as("找不到源码目录，测试本身失效")
                .isTrue();

        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (String needle : FORBIDDEN) {
                    if (code.contains(needle)) {
                        hits.add(file + " 含 " + needle);
                    }
                }
            }
        }

        assertThat(hits)
                .as("""
                        channel 又直连 IAM 的库了。IAM 要独立成平台，这条耦合必须先断掉——
                        否则 IAM 拆库或改表时，渠道会在运行时才发现，而失败表现是
                        「飞书绑定查不到」，与真实原因相距甚远。
                        改法：调 GET /iam/identity/resolve（见 ChannelIdentityService）""")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 扫描本身是有效的（否则上面那条永远通过）")
    void scanWouldCatchARegression() throws IOException {
        // 造一个含禁词的临时文件，确认扫描逻辑真的能命中。
        // 没有这条，上面那个测试在"路径写错/没扫到文件"时会静默通过
        Path dir = Files.createTempDirectory("scan-probe");
        try {
            Files.writeString(dir.resolve("Bad.java"),
                    "SELECT username FROM chatdata_iam.iam_user", StandardCharsets.UTF_8);

            List<String> hits = new ArrayList<>();
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                    for (String needle : FORBIDDEN) {
                        if (code.contains(needle)) {
                            hits.add(file + " 含 " + needle);
                        }
                    }
                }
            }
            assertThat(hits).hasSize(2);   // 库名与表名各一次
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * 剥掉 Java 的三种注释。
     *
     * <p>顺序很重要：**先剥块注释再剥行注释**。反过来的话，
     * {@code // 见 https://x} 里的 {@code //} 会被先处理，而块注释里
     * 又可能含 {@code //}，导致切错位置。
     *
     * <p>不处理字符串字面量里的 {@code //}——本项目里没有那种情况，
     * 而处理它需要真正的词法分析。如果以后出现了，这条测试会误报，
     * 那时再补。
     */
    private static String stripComments(String src) {
        // 块注释 /* ... */
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        // 行注释 // 到行尾
        return noBlock.replaceAll("(?m)//.*$", "");
    }
}
