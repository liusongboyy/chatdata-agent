package com.liusongboyy.chatdata.iam.security;

import com.liusongboyy.chatdata.iam.captcha.CaptchaService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 先校验验证码，再交给密码校验。
 *
 * <p>顺序是刻意的：验证码便宜且能挡自动化，BCrypt 一次约 100ms。
 * 先做便宜的那步，攻击者就无法用无效验证码反复触发 BCrypt。
 *
 * <p>验证码校验失败与密码错误都抛 {@link BadCredentialsException}，
 * 提示文案也保持一致——区分两者会给攻击者提供"验证码猜对了"的信号。
 */
public class CaptchaAuthenticationProvider implements AuthenticationProvider {

    private final CaptchaService captcha;
    private final DaoAuthenticationProvider delegate;

    public CaptchaAuthenticationProvider(CaptchaService captcha,
                                         UserDetailsService userDetailsService,
                                         PasswordEncoder passwordEncoder) {
        this.captcha = captcha;
        this.delegate = new DaoAuthenticationProvider(userDetailsService);
        this.delegate.setPasswordEncoder(passwordEncoder);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication.getDetails() instanceof CaptchaWebAuthenticationDetails details)) {
            throw new BadCredentialsException("登录请求缺少验证码");
        }
        if (!captcha.verifyAndConsume(details.getCheckKey(), details.getCaptcha())) {
            throw new BadCredentialsException("账号或密码错误");
        }
        Authentication result = delegate.authenticate(authentication);

        // 清掉 details：验证码是认证期的一次性输入，既不该随授权记录落库
        // （oauth2_authorization.attributes 会序列化 Authentication），
        // 也不该让自定义类型进入序列化链路——它没有 Jackson 反序列化入口。
        if (result instanceof UsernamePasswordAuthenticationToken token) {
            token.setDetails(null);
        }
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
