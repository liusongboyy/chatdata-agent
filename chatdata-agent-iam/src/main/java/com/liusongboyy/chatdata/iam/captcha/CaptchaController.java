package com.liusongboyy.chatdata.iam.captcha;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 验证码图片。key 由登录页生成，校验时原样带回。 */
@RestController
public class CaptchaController {

    private final CaptchaService captcha;

    public CaptchaController(CaptchaService captcha) {
        this.captcha = captcha;
    }

    @GetMapping("/captcha/{key}")
    public ResponseEntity<byte[]> image(@PathVariable String key) throws IOException {
        String safeKey = key.length() > 64 ? key.substring(0, 64) : key;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(captcha.generate(safeKey), "png", buffer);
        return ResponseEntity.ok()
                // 验证码不能进任何缓存：代理缓存会让多个用户拿到同一张图
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(buffer.toByteArray());
    }
}
