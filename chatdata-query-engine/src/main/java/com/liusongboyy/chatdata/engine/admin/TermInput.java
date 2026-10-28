package com.liusongboyy.chatdata.engine.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;

/**
 * 业务术语的创建/更新入参。
 *
 * @param name        术语名。通常是某个已登记的字段别名
 * @param synonyms    近义词。用户可能的各种说法
 * @param description 释义。**会进 prompt**，写给模型看
 * @param applyKind   应用方式
 */
public record TermInput(
        String name,
        List<String> synonyms,
        String description,
        String applyKind
) {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * 校验。
     *
     * <h2>只允许「仅释义」</h2>
     *
     * <p>厂商的模型还有「时间口径」与「数据筛选」两种，但那两种要**真正作用于渲染**
     * 才有意义。当前实现里它们不会影响 SQL——提供一个点了不生效的选项，
     * 比不提供更糟：管理员会以为配了就生效。
     *
     * <p>故这里**明确拒绝**这两种取值，并说明原因。等渲染侧接入了再放开。
     */
    public TermInput validated() {
        if (name == null || name.isBlank()) {
            throw new AdminException("术语名不能为空");
        }

        String kind = (applyKind == null || applyKind.isBlank()) ? "none" : applyKind.trim();
        if (!"none".equalsIgnoreCase(kind)) {
            throw new AdminException(
                    "术语的应用方式目前只支持「仅释义」。"
                            + "「时间口径」与「数据筛选」需要渲染侧支持才会真正生效，"
                            + "当前尚未接入——配了也不会影响查询结果。");
        }

        return new TermInput(name.trim(),
                synonyms == null ? List.of() : List.copyOf(synonyms),
                description == null ? null : description.trim(),
                "none");
    }

    /** 序列化成 {@code synonyms_json}。 */
    public String synonymsJson() {
        ArrayNode arr = M.createArrayNode();
        (synonyms == null ? List.<String>of() : synonyms).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .forEach(arr::add);
        return arr.toString();
    }
}
