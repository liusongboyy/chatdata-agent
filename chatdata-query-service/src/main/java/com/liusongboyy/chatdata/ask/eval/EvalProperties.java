package com.liusongboyy.chatdata.ask.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 评测入口的配置。
 *
 * <p>路径默认是仓库根目录的 {@code eval/} 目录——**一个数据集一个 JSON**。
 * 接新数据源/新数据集时只加文件，不改配置、不改代码。
 */
@ConfigurationProperties(prefix = "chatdata.eval")
public class EvalProperties {

    /**
     * 评测集路径：可以是单个 JSON 文件，也可以是包含多个 {@code *.json} 的目录。
     */
    private String path = "../eval";

    /** 报告输出路径（JSON）。 */
    private String report = "target/eval-report.json";

    /**
     * 通过率下限。低于它就返回非 0 退出码，供 CI 当门禁。
     * 默认 0：本地先看分数，不阻断。
     */
    private double minPassRate = 0.0;

    /** 只跑前 N 条（{@code <= 0} 表示全量）。调 prompt 时用少量用例快速迭代。 */
    private int limit = 0;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public double getMinPassRate() {
        return minPassRate;
    }

    public void setMinPassRate(double minPassRate) {
        this.minPassRate = minPassRate;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }
}
