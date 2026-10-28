package com.liusongboyy.chatdata.ask.conversation;

import com.liusongboyy.chatdata.ask.identity.CurrentUser;
import com.liusongboyy.chatdata.engine.agent.AskSessionProperties;
import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话历史接口。
 *
 * <p>身份一律从 {@link CurrentUser} 取；客户端只能传分页、搜索、助理筛选，
 * **不能指定归属人**。详情/删除不属于当前用户时返回 404，不泄露存在性。
 *
 * <p>路径放在 {@code /api/ask/conversations} 而不是复用
 * {@code /api/ask/engine}：前者是产品数据，后者是问数入口，混在一起会让
 * 路由与权限审计都变模糊。
 */
@RestController
@RequestMapping("/api/ask/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final CurrentUser currentUser;
    private final ConversationService conversations;
    private final ObjectProvider<AgentStateStore> stateStore;
    private final AskSessionProperties sessionProps;

    public ConversationController(CurrentUser currentUser,
                                  ConversationService conversations,
                                  ObjectProvider<AgentStateStore> stateStore,
                                  AskSessionProperties sessionProps) {
        this.currentUser = currentUser;
        this.conversations = conversations;
        this.stateStore = stateStore;
        this.sessionProps = sessionProps;
    }

    @GetMapping
    public ConversationService.ConversationPage list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String assistantId) {
        return conversations.list(requireUsername(), page, size, keyword, assistantId);
    }

    @GetMapping("/{id}")
    public ConversationService.ConversationDetail detail(@PathVariable String id) {
        return conversations.detail(id, requireUsername());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        String username = requireUsername();
        conversations.delete(id, username);

        // 历史记录删了，agentscope 的上下文也尽力清掉——否则用户"删了会话"
        // 之后继续用同一个 id 提问，模型还会记着删掉的内容。
        // 清理失败不回滚删除：列表已经不可见，残留状态下次保留任务会收走。
        if (sessionProps.isEnabled()) {
            try {
                stateStore.getObject().delete(username, id);
            } catch (Exception e) {
                log.warn("清理 agentscope 会话状态失败（会话已从历史中删除）：id={} 原因={}",
                        id, e.getMessage());
            }
        }
        return ResponseEntity.noContent().build();
    }

    private String requireUsername() {
        return currentUser.username()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED));
    }
}
