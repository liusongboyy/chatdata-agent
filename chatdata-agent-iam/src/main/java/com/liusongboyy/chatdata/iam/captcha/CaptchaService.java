package com.liusongboyy.chatdata.iam.captcha;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.security.SecureRandom;
import java.time.Duration;

/**
 * 图形验证码，状态存 Redis。
 *
 * <p>与 ask 旧实现的两点差别：
 * <ul>
 *   <li>存 Redis 而不是进程内 Map——IAM 要多实例部署，内存 Map 会让用户在
 *       负载均衡下随机遇到"验证码错误"</li>
 *   <li>校验即删除（get-and-delete）——同一张图不能反复试，否则暴力破解的
 *       代价会从"每次识别一张图"降到"识别一次、试一万次"</li>
 * </ul>
 */
@Service
public class CaptchaService {

    /** 刻意排除易混字符（0/O、1/I/l）。 */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final Duration TTL = Duration.ofSeconds(120);

    private final SecureRandom random = new SecureRandom();
    private final StringRedisTemplate redis;

    public CaptchaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public BufferedImage generate(String key) {
        String code = randomCode();
        redis.opsForValue().set(redisKey(key), code, TTL);
        return draw(code);
    }

    /**
     * 校验并立即销毁。无论成功与否都删除：
     * 失败后必须重新取图，否则同一个验证码可以被反复尝试。
     */
    public boolean verifyAndConsume(String key, String input) {
        if (key == null || key.isBlank() || input == null || input.isBlank()) {
            return false;
        }
        String stored = redis.opsForValue().getAndDelete(redisKey(key));
        return stored != null && stored.equalsIgnoreCase(input.trim());
    }

    private static String redisKey(String key) {
        return "iam:captcha:" + key;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    private BufferedImage draw(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(249, 249, 249));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 6; i++) {
                g.setColor(new Color(150 + random.nextInt(80), 150 + random.nextInt(80),
                        150 + random.nextInt(80)));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            for (int i = 0; i < code.length(); i++) {
                g.setColor(new Color(30 + random.nextInt(90), 30 + random.nextInt(90),
                        120 + random.nextInt(90)));
                int x = 14 + i * 26 + random.nextInt(5);
                int y = 30 + random.nextInt(6);
                g.drawString(String.valueOf(code.charAt(i)), x, y);
            }
        } finally {
            g.dispose();
        }
        return image;
    }
}
