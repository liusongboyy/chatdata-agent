package com.liusongboyy.chatdata.channel;

import com.liusongboyy.chatdata.channel.config.ChannelProperties;
import com.liusongboyy.chatdata.channel.config.FeishuProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 渠道服务。
 *
 * <h2>它为什么是独立进程</h2>
 *
 * <p>入站流量来自飞书，不是浏览器。混进网关会让「给浏览器鉴权」的那套配置
 * 去处理飞书的签名请求，两边都不自然。独立进程还带来两个实际好处：
 * 公网只需放行 {@code /channel/feishu/event} 一个路径；渠道故障不影响 Web 问数。
 *
 * <h2>它不碰权限</h2>
 *
 * <p>渠道只做「哪个用户问了什么 → 拿他的令牌去问 → 把结果排版回去」。
 * 行级规则、列脱敏、角色判定全在 ask 里，这里看不到也不该看到。
 * 一旦这个模块开始判断「这个人能不能查」，就说明边界破了。
 */
@SpringBootApplication
@EnableConfigurationProperties({ChannelProperties.class, FeishuProperties.class})
public class ChannelApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelApplication.class, args);
    }
}
