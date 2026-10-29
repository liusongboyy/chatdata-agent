package com.liusongboyy.chatdata.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息里的命令解析。
 *
 * <p>这些字符串是用户**在 IM 里手打**的，不是接口参数——所以容错要松，
 * 但不能松到把正常提问误判成命令。「#助手 2」是命令，「助手的表现怎么样」不是。
 */
class MessageHandlerCommandTest {

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("★ 助理选择命令的解析")
    @CsvSource({
            "#助手 1,      1",
            "#助手 2,      2",
            "#助理 3,      3",
            "＃助手 1,     1",      // 全角井号：中文输入法下很容易打出来
            "  #助手 2  ,  2",     // 前后空白
            "#助手 12,     12",
    })
    void parsesPickCommand(String input, int expected) {
        assertThat(MessageHandler.parseAssistantPick(input)).contains(expected);
    }

    @Test
    @DisplayName("★ 只说「#助手」没给编号 → 给个占位，由调用方提示")
    void pickWithoutIndex() {
        assertThat(MessageHandler.parseAssistantPick("#助手")).contains(-1);
        assertThat(MessageHandler.parseAssistantPick("#助手  abc")).contains(-1);
    }

    @ParameterizedTest
    @DisplayName("★ 正常提问不能被误判成命令")
    @ValueSource(strings = {
            "订单金额合计是多少",
            "助手每个月花了多少钱",
            "打卡次数是多少",
            "助手的表现怎么样",
            "#hashtag 是话题",
            "",
    })
    void normalQuestionIsNotCommand(String input) {
        assertThat(MessageHandler.parseAssistantPick(input)).isEmpty();
    }

    @ParameterizedTest(name = "能力询问：{0}")
    @DisplayName("★ 认得出「你能问什么」这类能力询问")
    @ValueSource(strings = {
            "我可以问你哪些方面的数据呢",
            "你能问哪些数据",
            "支持什么问题",
            "你能干什么",
            "有哪些指标可以查",
            "你可以问什么内容",
            "这个助手会什么",
    })
    void detectsCapabilityQuestion(String input) {
        assertThat(MessageHandler.isCapabilityQuestion(input)).isTrue();
    }

    @ParameterizedTest(name = "真问题不该误判：{0}")
    @DisplayName("★ 正常提问不能被误判成能力询问（误判的代价是答不出用户真正问的）")
    @ValueSource(strings = {
            "订单金额合计是多少",
            "打卡次数是多少",
            "上周华东的订单金额",
            "订单能问出多少金额",                    // 有「能问」但不是在问能力范围
            "帮我看看哪些门店的销量比上个月下降超过一成", // 太长，大概率是真问题
            "保费收入这个指标怎么算的",
            "",
    })
    void realQuestionIsNotCapability(String input) {
        assertThat(MessageHandler.isCapabilityQuestion(input)).isFalse();
    }

    @Test
    @DisplayName("带 # 前缀的能力询问也认（用户可能习惯加前缀）")
    void capabilityWithHashPrefix() {
        assertThat(MessageHandler.isCapabilityQuestion("#你能问哪些数据")).isTrue();
    }

    @Test
    @DisplayName("重置命令：只认精确的那几个词，不做模糊匹配")
    void resetCommands() {
        assertThat(MessageHandler.isResetCommand("新话题")).isTrue();
        assertThat(MessageHandler.isResetCommand("重置")).isTrue();
        assertThat(MessageHandler.isResetCommand(" /reset ")).isTrue();
        // 含「重置」但不是命令的句子不该被吞掉
        assertThat(MessageHandler.isResetCommand("帮我把筛选条件重置一下")).isFalse();
    }
}
