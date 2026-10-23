package com.liusongboyy.chatdata.iam.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * 登录表单里除用户名/密码外的两个字段。
 *
 * <p>用 {@code AuthenticationDetails} 携带而不是塞进请求属性：
 * 认证提供者只拿得到 Authentication，请求对象在那一层已经不可见。
 */
public class CaptchaWebAuthenticationDetails extends WebAuthenticationDetails {

    private final String captcha;
    private final String checkKey;

    public CaptchaWebAuthenticationDetails(HttpServletRequest request) {
        super(request);
        this.captcha = request.getParameter("captcha");
        this.checkKey = request.getParameter("checkKey");
    }

    /**
     * 供 JSON 登录接口使用：请求体不是表单参数，{@code getParameter} 取不到。
     */
    public CaptchaWebAuthenticationDetails(String remoteAddress, String sessionId,
                                           String captcha, String checkKey) {
        super(remoteAddress, sessionId);
        this.captcha = captcha;
        this.checkKey = checkKey;
    }

    public String getCaptcha() {
        return captcha;
    }

    public String getCheckKey() {
        return checkKey;
    }
}
