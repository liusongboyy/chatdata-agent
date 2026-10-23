package com.liusongboyy.chatdata.engine.semantic;

import java.util.List;

/**
 * 业务术语。
 *
 * <h2>它解决的是一个实测到的问题</h2>
 *
 * <p>问「保费<strong>合计</strong>是多少」，模型产出 {@code metrics: ["保费合计"]}——
 * 它跟着**用户的措辞**走，而登记的别名是「保费」。当时的补救是一个重试循环：
 * 渲染失败后把可用字段名回灌给模型再试一次。
 *
 * <p>术语是**从源头解决**：把「保费合计」「保费金额」这些说法显式登记成「保费」的近义词，
 * 模型一开始就知道它们是一回事，不必等失败。
 *
 * <h2>{@code applyKind} 目前只用到 NONE</h2>
 *
 * <p>厂商的模型有三种应用方式，后两种（时间口径 / 数据筛选）要**真正作用于渲染**
 * 才有意义。本轮只把术语作为近义词与释义喂进 prompt，故界面只暴露「仅释义」——
 * 提供一个点了不生效的选项比不提供更糟。字段保留是为了将来扩展不必改表。
 *
 * @param name        术语名。通常是某个已登记的字段别名
 * @param synonyms    近义词。用户可能的各种说法
 * @param description 释义。**会进 prompt**，写给模型看，所以要说人话
 * @param applyKind   应用方式
 */
public record Term(
        String name,
        List<String> synonyms,
        String description,
        ApplyKind applyKind
) {

    public enum ApplyKind {
        /** 仅释义：进 prompt 帮模型理解，不影响 SQL。**目前只支持这一种。** */
        NONE,
        /** 时间口径：把术语替换成某个时间范围。**尚未接入渲染。** */
        TIME,
        /** 数据筛选：追加 WHERE 条件。**尚未接入渲染。** */
        FILTER
    }

    public Term {
        if (name == null || name.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "术语缺少名称");
        }
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        applyKind = applyKind == null ? ApplyKind.NONE : applyKind;
    }

    /** 供 prompt 用：本术语的全部说法（术语名 + 近义词）。 */
    public List<String> allPhrasings() {
        List<String> out = new java.util.ArrayList<>();
        out.add(name);
        synonyms.stream().filter(s -> !s.equals(name)).forEach(out::add);
        return List.copyOf(out);
    }

    public static ApplyKind parseApplyKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return ApplyKind.NONE;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "none", "仅释义" -> ApplyKind.NONE;
            case "time", "时间口径" -> ApplyKind.TIME;
            case "filter", "数据筛选" -> ApplyKind.FILTER;
            default -> throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "不支持的术语应用方式：" + raw + "（只支持 none/time/filter）");
        };
    }
}
