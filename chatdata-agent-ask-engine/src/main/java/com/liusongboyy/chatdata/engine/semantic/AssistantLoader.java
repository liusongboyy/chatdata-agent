package com.liusongboyy.chatdata.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.permission.AssistantPermission;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@code ask_assistant} 的行解析成 {@link Assistant}。
 *
 * <p>与 {@code DomainLoader} 分开：助理与语义域是两条独立的配置线，
 * 它们的加载失败原因不同、排查路径也不同。混在一个类里会让报错上下文变模糊。
 */
public final class AssistantLoader {

    private static final ObjectMapper M = new ObjectMapper();

    private AssistantLoader() {
    }

    public static Assistant load(AssistantRow row) {
        String where = "助理「%s」(id=%d)".formatted(row.name(), row.id());
        try {
            return new Assistant(
                    String.valueOf(row.id()),
                    row.name(),
                    row.avatar(),
                    row.description(),
                    row.welcome(),
                    row.domainId() == null ? null : String.valueOf(row.domainId()),
                    parseStringList(row.domainIds(), where + " 的 domain_ids"),
                    row.showSql(),
                    Assistant.Status.parse(row.status()),
                    parseStringList(row.questionsJson(), where + " 的 questions_json"),
                    loadPermission(row.permJson(), where));
        } catch (SemanticException e) {
            throw new SemanticException(e.reason(), where + "：" + e.getMessage());
        }
    }

    private static AssistantPermission loadPermission(String json, String where) {
        if (json == null || json.isBlank()) {
            // 没有权限配置 = 未启用访问控制 = **拒绝**。
            // 厂商在缺 enabled 键时是放行，本项目改掉了——见 AssistantPermission 的说明
            return AssistantPermission.from(null);
        }
        try {
            return AssistantPermission.from(M.readTree(json));
        } catch (Exception e) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    where + " 的 perm_json 解析失败：" + e.getMessage());
        }
    }

    private static List<String> parseStringList(String json, String where) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = M.readTree(json);
            if (!arr.isArray()) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        where + " 不是数组");
            }
            List<String> out = new ArrayList<>();
            arr.forEach(n -> {
                String v = n.asText();
                if (v != null && !v.isBlank()) {
                    out.add(v.trim());
                }
            });
            return out;
        } catch (SemanticException e) {
            throw e;
        } catch (Exception e) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    where + " 解析失败：" + e.getMessage());
        }
    }
}
