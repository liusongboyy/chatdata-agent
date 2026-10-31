package com.liusongboyy.chatdata.mesh.ask;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ask 的超时行为。
 *
 * <p>它测的不是「慢」，是<b>卡住时谁先死</b>。
 *
 * <p>没有读超时时，RestClient 会一直等，直到控制面的 conversation deadline
 * （5 分钟）到点——那时用户看到的是 {@code conversation_timeout}，
 * 一个和真实原因（问数卡住）毫无关系的错误码。
 */
class AskClientTimeoutTest {

    /** 起一个只握手不回话的假 ask，用来制造「连上了但没回」。 */
    private static HttpServer silentAsk() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // 开一个 SSE 头，然后什么都不发——真实地模拟「ask 卡在模型调用上」
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                Thread.sleep(60_000);       // 远长于下面配的 1 秒超时
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("★ 读超时生效：ask 不回话时在配置的时间附近失败，而不是挂到别人的 deadline")
    void readTimeoutFires() throws IOException {
        HttpServer server = silentAsk();
        try {
            MeshProperties props = new MeshProperties();
            props.getAsk().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            props.getAsk().setTimeout(Duration.ofSeconds(1));
            props.getA2a().setDomainId("2300");

            AskClient client = new AskClient(props);
            AtomicLong elapsed = new AtomicLong();

            long start = System.currentTimeMillis();
            assertThatThrownBy(() -> client.ask("token", "各门店的订单金额合计", null, false))
                    .isInstanceOf(AskClient.AskFailedException.class);
            elapsed.set(System.currentTimeMillis() - start);

            // 宽松上界：只证明「是超时掐断的」，不是「挂了 5 分钟」
            assertThat(elapsed.get())
                    .as("读超时应在 1 秒配置附近触发，实际等了 %d ms", elapsed.get())
                    .isLessThan(5_000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("★ 建连超时生效：ask 完全不可达时几秒内失败")
    void connectTimeoutFires() {
        MeshProperties props = new MeshProperties();
        // 保留一个必然没人监听的端口
        props.getAsk().setBaseUrl("http://127.0.0.1:1");
        props.getAsk().setConnectTimeout(Duration.ofSeconds(1));
        props.getAsk().setTimeout(Duration.ofSeconds(2));
        props.getA2a().setDomainId("2300");

        AskClient client = new AskClient(props);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.ask("token", "各门店的订单金额合计", null, false))
                .isInstanceOf(AskClient.AskFailedException.class);
        assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
    }
}
