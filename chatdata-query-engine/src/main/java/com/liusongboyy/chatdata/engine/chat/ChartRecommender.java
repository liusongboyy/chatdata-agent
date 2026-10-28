package com.liusongboyy.chatdata.engine.chat;

import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.FieldRef;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;

import java.util.List;
import java.util.Locale;

/**
 * 按数据与意图推荐图表形态。
 *
 * <h2>先问"是不是图"，再问"哪种图"</h2>
 *
 * <p>按 dataviz 的 form 判断顺序：
 * <ul>
 *   <li><b>一个值 → 数字卡</b>，不画图。单条柱或两片饼是把数字包装成图，更难读</li>
 *   <li><b>时间维度 → 折线</b>，看趋势</li>
 *   <li><b>分类维度 → 柱状</b>，比量级</li>
 *   <li>其余 → 表格</li>
 * </ul>
 *
 * <p><strong>不主动推荐饼图。</strong>它只在"部分与整体、且段数少"时合适，
 * 而这两点从数据形状看不出来（3 个区域的保费既可以比量级也可以看占比）。
 * 由用户在界面上自己切——推荐一个错误的饼图，比不推荐更糟。
 *
 * <h2>维度是不是日期，靠语义层而不是列名</h2>
 *
 * <p>"月份"这样的列名可以猜，但猜错的表现是"趋势图画成了柱状图"，
 * 不报错、只是不对。故这里解析到 {@code ColumnDef.type} 再判断。
 */
public final class ChartRecommender {

    /** 饼图/柱状图能舒适容纳的段数上限。超过它，相邻颜色开始糊在一起。 */
    private static final int COMFORTABLE_SEGMENTS = 6;

    private ChartRecommender() {
    }

    public static ChartSpec recommend(AskDsl dsl, SemanticModel model, QueryResult result) {
        if (result.columns().isEmpty()) {
            return ChartSpec.table("没有可展示的列");
        }

        List<String> dims = dsl.dimensions();
        List<String> metrics = dsl.metrics();

        // ① 没有维度：要么是单个聚合值，要么是多个并列的聚合值
        if (dims.isEmpty()) {
            if (metrics.isEmpty()) {
                return ChartSpec.table("没有指标也没有维度");
            }
            if (result.rowCount() == 1) {
                return new ChartSpec(ChartSpec.Form.STAT, null, metrics, null,
                        "只有一个值，用数字卡比画图更清楚");
            }
            // 无维度却多行：数据形状与意图不符（正常不该出现），退回表格
            return ChartSpec.table("没有分组维度但返回了多行，用表格更稳妥");
        }

        if (metrics.isEmpty()) {
            // 只选了维度：这是"有哪些取值"，表格比图有用
            return ChartSpec.table("只选了维度，用表格列出取值更有用");
        }

        String category = dims.get(0);
        String series = dims.size() > 1 ? dims.get(1) : null;

        // ② 分类轴是日期 → 趋势
        if (isDateField(model, category)) {
            return new ChartSpec(ChartSpec.Form.LINE, category, metrics, series,
                    "「%s」是日期，折线能看出趋势".formatted(category));
        }

        // ③ 分类轴不是日期 → 比量级
        return new ChartSpec(ChartSpec.Form.BAR, category, metrics, series,
                result.rowCount() <= COMFORTABLE_SEGMENTS
                        ? "按「%s」比较量级；%d 段也可以切成饼图看占比".formatted(category, result.rowCount())
                        : "按「%s」比较量级；%d 段较多，饼图会看不清".formatted(category, result.rowCount()));
    }

    /**
     * 这个字段是不是日期。
     *
     * <p>解析不到（字段不在域内）时返回 false —— 退回柱状图，
     * 那是个安全的选择：柱状图对任何分类轴都成立，折线图对非时间轴是错的。
     */
    private static boolean isDateField(SemanticModel model, String fieldName) {
        try {
            // 与渲染器**共用同一个判断**（SemanticModel.isTemporal）：
            // 两边各判一次的话，一旦口径不同就会出现"SQL 按天分组、图表却画成折线"
            // 这类不一致，而两边单看都对
            return model.isTemporal(model.resolveAny(fieldName));
        } catch (SemanticException e) {
            return false;
        }
    }
}
