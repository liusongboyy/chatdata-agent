package com.liusongboyy.chatdata.engine.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.liusongboyy.chatdata.engine.permission.AssistantPermission;

import java.util.List;

/**
 * 助理的创建/更新入参。
 *
 * @param name        名称
 * @param avatar      头像 emoji
 * @param description 描述
 * @param welcome     欢迎语
 * @param domainId    主数据域 id
 * @param domainIds   绑定的全部域 id
 * @param showSql     是否展示 SQL
 * @param status      online / draft / offline
 * @param questions   推荐问题
 * @param permJson    访问权限 JSON
 */
public record AssistantInput(
        String name,
        String avatar,
        String description,
        String welcome,
        Long domainId,
        List<String> domainIds,
        boolean showSql,
        String status,
        List<String> questions,
        String permJson
) {

    private static final ObjectMapper M = new ObjectMapper();

    public AssistantInput validated() {
        if (name == null || name.isBlank()) {
            throw new AdminException("助理名称不能为空");
        }

        String st = (status == null || status.isBlank()) ? "draft" : status.trim();
        if (!List.of("online", "draft", "offline").contains(st)) {
            throw new AdminException("状态只能是 online / draft / offline，实际：" + status);
        }

        String perm = (permJson == null || permJson.isBlank()) ? null : permJson.trim();
        if (perm != null) {
            AssistantPermission parsed;
            try {
                parsed = AssistantPermission.from(M.readTree(perm));
            } catch (Exception e) {
                throw new AdminException("访问权限不是合法 JSON：" + e.getMessage());
            }

            // **上线中的助理必须有有效授权。**
            // 厂商在缺 enabled 键时对任何角色集放行（包括空集），本项目改成默认拒绝。
            // 这里在源头拦一次，免得配完上线了才发现谁都进不去——
            // 或者更糟：以为限制了，其实谁都能进。
            if ("online".equals(st) && !parsed.hasAnyGrant()) {
                throw new AdminException(
                        "上线中的助理必须配置访问权限：enabled 设为 true，"
                                + "且 grantRoles / grantUsers 至少填一个。"
                                + "（若只是草稿，请把状态设为 draft）");
            }
        } else if ("online".equals(st)) {
            throw new AdminException("上线中的助理必须配置访问权限。若只是草稿，请把状态设为 draft");
        }

        return new AssistantInput(name.trim(), blankToNull(avatar), blankToNull(description),
                blankToNull(welcome), domainId,
                domainIds == null ? List.of() : List.copyOf(domainIds),
                showSql, st,
                questions == null ? List.of() : List.copyOf(questions),
                perm);
    }

    public String domainIdsJson() {
        ArrayNode arr = M.createArrayNode();
        (domainIds == null ? List.<String>of() : domainIds).stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .forEach(arr::add);
        return arr.isEmpty() ? null : arr.toString();
    }

    public String questionsJson() {
        ArrayNode arr = M.createArrayNode();
        (questions == null ? List.<String>of() : questions).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .forEach(arr::add);
        return arr.isEmpty() ? null : arr.toString();
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
