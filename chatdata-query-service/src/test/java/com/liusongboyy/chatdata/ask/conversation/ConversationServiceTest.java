package com.liusongboyy.chatdata.ask.conversation;

import com.liusongboyy.chatdata.ask.conversation.mapper.ConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话历史的服务层。
 *
 * <h2>这一层最要紧的是三件事</h2>
 *
 * <ol>
 *   <li><b>归属只认令牌</b>——别人的 id 一律 404，不泄露"这条存在"</li>
 *   <li><b>分页与关键词夹取</b>——page=0 / size=99999 / 超长关键词都不能把库拖垮</li>
 *   <li><b>写消息是尽力而为</b>——历史写失败不能让用户的问题白问</li>
 * </ol>
 *
 * <p>所以这些用例断言的是**调用参数**与**异常类型**，而不是业务结果——
 * 结果由 SQL 决定，那是真库测试的事。
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationMapper mapper;

    /** 真的 TransactionTemplate 会去要连接；这里只要它把 lambda 跑一遍。 */
    private ConversationService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = new org.springframework.transaction.support
                .AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, org.springframework.transaction
                    .TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(org.springframework.transaction.support
                    .DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(org.springframework.transaction.support
                    .DefaultTransactionStatus status) {
            }
        };
        service = new ConversationService(mapper, tm);
    }

    @Nested
    @DisplayName("分页与搜索")
    class Paging {

        @Test
        @DisplayName("★ page<1 归 1，size 超上限夹到 100")
        void clampsPaging() {
            when(mapper.count(any(), any(), any())).thenReturn(0L);

            ConversationService.ConversationPage page =
                    service.list("u1", 0, 99_999, null, null);

            assertThat(page.page()).isEqualTo(1);
            assertThat(page.size()).isEqualTo(100);
        }

        @Test
        @DisplayName("size<=0 用默认 20")
        void defaultSize() {
            when(mapper.count(any(), any(), any())).thenReturn(0L);

            assertThat(service.list("u1", 1, 0, null, null).size()).isEqualTo(20);
        }

        @Test
        @DisplayName("★ 关键词里的 % 和 _ 被转义，不能变成通配符")
        void escapesWildcards() {
            assertThat(ConversationService.likePattern("50%_off"))
                    .isEqualTo("%50!%!_off%");
        }

        @Test
        @DisplayName("关键词前后的空白去掉；空关键词变为「不过滤」")
        void trimsKeyword() {
            assertThat(ConversationService.likePattern("  保费  ")).isEqualTo("%保费%");
            assertThat(ConversationService.likePattern("   ")).isNull();
        }

        @Test
        @DisplayName("总数为 0 时不去查列表（省一次无意义的全表扫）")
        void skipsQueryWhenEmpty() {
            when(mapper.count(any(), any(), any())).thenReturn(0L);

            assertThat(service.list("u1", 1, 20, null, null).items()).isEmpty();
            verify(mapper, never()).selectPage(anyString(), any(), any(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("归属")
    class Ownership {

        @Test
        @DisplayName("★ 别人的会话 → 404（不是 403，不泄露存在性）")
        void otherUsersConversationIs404() {
            when(mapper.selectOwner("c1")).thenReturn(Map.of("username", "u2", "deleted", 0));

            assertThatThrownBy(() -> service.detail("c1", "u1"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("已软删除的会话也按 404 处理")
        void deletedIs404() {
            when(mapper.selectOwner("c1")).thenReturn(Map.of("username", "u1", "deleted", 1));

            assertThatThrownBy(() -> service.detail("c1", "u1"))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("没有身份 → 401")
        void anonymousIs401() {
            assertThatThrownBy(() -> service.detail("c1", " "))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.UNAUTHORIZED));
        }

        @Test
        @DisplayName("删除命中 0 行 → 404（与查详情同一口径）")
        void deleteMissingIs404() {
            when(mapper.softDelete("c1", "u1")).thenReturn(0);

            assertThatThrownBy(() -> service.delete("c1", "u1"))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("写消息是尽力而为")
    class BestEffort {

        @Test
        @DisplayName("★ 写消息抛异常时**不向上抛** —— 历史失败不能让本次问数白问")
        void swallowWriteFailure() {
            when(mapper.lockMessageCount("c1")).thenReturn(0);
            when(mapper.insertMessage(any())).thenThrow(new RuntimeException("库挂了"));

            // 不抛就是通过
            service.appendUser("c1", "问题", "question");
        }

        @Test
        @DisplayName("会话已被删除（锁不到行）时跳过写入，不报错")
        void skipWhenConversationGone() {
            when(mapper.lockMessageCount("c1")).thenReturn(null);

            service.appendUser("c1", "问题", "question");

            verify(mapper, never()).insertMessage(any());
        }
    }
}
