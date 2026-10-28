package com.liusongboyy.chatdata.ask.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.ask.policy.mapper.PolicyAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 记录数据域/助理策略的创建与更新。
 *
 * <p><b>审计写失败不回滚业务保存</b>：保存本身已成功，此时抛错只会让管理员
 * 以为没保存而重复操作。失败记 ERROR，交给日志告警。
 *
 * <p>摘要对**去掉空白后的 JSON** 计算：同样的策略只是缩进不同时，
 * 摘要保持一致，否则"内容没变"会被记成一次变更。
 */
@Component
public class PolicyAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(PolicyAuditRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PolicyAuditMapper mapper;

    public PolicyAuditRecorder(PolicyAuditMapper mapper) {
        this.mapper = mapper;
    }

    public void recordCreate(String resourceType, long resourceId, String resourceName,
                             String operator, String permJson) {
        record(resourceType, resourceId, resourceName, "CREATE", operator, permJson);
    }

    public void recordUpdate(String resourceType, long resourceId, String resourceName,
                             String operator, String permJson) {
        record(resourceType, resourceId, resourceName, "UPDATE", operator, permJson);
    }

    private void record(String resourceType, long resourceId, String resourceName,
                        String action, String operator, String permJson) {
        try {
            mapper.insert(resourceType, resourceId, resourceName, action, operator,
                    digest(permJson), LocalDateTime.now());
        } catch (RuntimeException e) {
            log.error("策略变更审计写入失败：{} {} id={} action={} operator={}",
                    resourceType, resourceName, resourceId, action, operator, e);
        }
    }

    /**
     * 无空白 SHA-256；空策略与 null 视为同一个值。
     *
     * <p>走 JSON 解析而不是正则去空白：后者会把**字符串值里的空格**也吃掉，
     * 让 {@code "华东 一区"} 与 {@code "华东一区"} 得到同一个摘要。
     * 解析失败（保存前已由输入校验拦截）时退回原文摘要，至少不丢审计。
     */
    public static String digest(String permJson) {
        byte[] payload;
        if (permJson == null || permJson.isBlank()) {
            payload = new byte[0];
        } else {
            try {
                payload = JSON.writeValueAsBytes(JSON.readTree(permJson));
            } catch (Exception e) {
                payload = permJson.getBytes(StandardCharsets.UTF_8);
            }
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
