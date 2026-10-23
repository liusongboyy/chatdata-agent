package com.liusongboyy.chatdata.engine.chat;

import java.util.List;

/**
 * 图表规格：用哪种形态、哪个字段作分类轴、哪些作数值、哪个作系列。
 *
 * <h2>为什么由后端推荐</h2>
 *
 * <p>后端知道**意图**（DSL 里的指标/维度/时间范围），前端只知道结果的形状。
 * 「这个维度是日期列吗」这种判断需要语义层的类型信息——前端拿不到，
 * 只能靠列名猜。
 *
 * <h2>推荐的是**默认形态**，不是唯一选择</h2>
 *
 * <p>前端提供形态切换（表格 / 柱 / 折 / 饼 / 数字卡），只禁用数据不支持的。
 * 推荐一个最好的默认，比强制一种形态有用。
 *
 * @param form        推荐形态
 * @param categoryField 分类轴字段（柱/折/饼用）
 * @param valueFields   数值字段，可多个（分组柱）
 * @param seriesField   系列字段，用于第二维度分组；无则为 null
 * @param reason        推荐理由，展示给用户看，便于理解为什么是这个形态
 */
public record ChartSpec(
        Form form,
        String categoryField,
        List<String> valueFields,
        String seriesField,
        String reason
) {

    public enum Form {
        /**
         * 数字卡。**只有一个值时不画图**——单条柱或两片饼是把数字包装成图，
         * 反而更难读（见 dataviz 的 form 反模式）。
         */
        STAT,
        /** 柱状图。比较量级。 */
        BAR,
        /** 折线图。看趋势。 */
        LINE,
        /** 饼图。看部分与整体，段数少时可用。 */
        PIE,
        /** 表格。形态不适合画图时的兜底，也是任何时候都能切过去的视图。 */
        TABLE
    }

    public ChartSpec {
        valueFields = valueFields == null ? List.of() : List.copyOf(valueFields);
    }

    public static ChartSpec table(String reason) {
        return new ChartSpec(Form.TABLE, null, List.of(), null, reason);
    }
}
