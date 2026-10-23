package com.liusongboyy.chatdata.engine.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.liusongboyy.chatdata.engine.semantic.Topic;

import java.util.List;

/**
 * 查询主题的创建/更新入参。
 *
 * @param name        主题名称
 * @param description 描述
 * @param status      published / draft
 * @param fields      字段引用，形如 {@code ["ins_policy.premium"]}
 */
public record TopicInput(
        String name,
        String description,
        String status,
        List<String> fields
) {

    private static final ObjectMapper M = new ObjectMapper();

    public TopicInput validated() {
        if (name == null || name.isBlank()) {
            throw new AdminException("主题名称不能为空");
        }

        String st = (status == null || status.isBlank()) ? "draft" : status.trim();
        if (!"published".equals(st) && !"draft".equals(st)) {
            throw new AdminException("状态只能是 published 或 draft，实际：" + status);
        }

        List<String> fs = fields == null ? List.of() : fields.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();

        // 只校验形状（表名.列名）。表与列是否真实存在由 AdminDomainService 校验——
        // 那里能拿到域内的注册表，这里拿不到。同一个东西在两处校验会分叉
        List<String> malformed = fs.stream().filter(f -> !Topic.isWellFormed(f)).toList();
        if (!malformed.isEmpty()) {
            throw new AdminException(
                    "这些字段引用格式不对：%s。应当写成「表名.列名」的形式。"
                            .formatted(String.join("、", malformed)));
        }

        return new TopicInput(name.trim(), description == null ? null : description.trim(), st, fs);
    }

    /** 序列化成 {@code fields_json}。 */
    public String fieldsJson() {
        ArrayNode arr = M.createArrayNode();
        (fields == null ? List.<String>of() : fields).forEach(arr::add);
        return arr.toString();
    }
}
