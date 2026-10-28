package com.liusongboyy.chatdata.ask.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liusongboyy.chatdata.ask.audit.AskAuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * Mapper 单独放在 {@code mapper} 子包，而不是与 Service 接口同包。
 *
 * <p>原因：{@code @MapperScan} 扫的是**整个包**，而 MyBatis 的扫描器会把包内
 * **所有接口**都注册成 mapper。若 Service 接口与 Mapper 同包，它也会被注册成一个
 * 类型相同的 bean，导致注入时报 "required a single bean, but 2 were found"——
 * 报错信息指向 bean 冲突，不会提示"某接口被误当作 mapper"，极难定位。
 */
@Mapper
public interface AskAuditMapper extends BaseMapper<AskAuditLog> {

    /** 删除早于给定时刻的记录。返回删除条数。 */
    @Delete("DELETE FROM ask_audit_log WHERE created_at < #{before}")
    int deleteCreatedBefore(@Param("before") LocalDateTime before);
}
