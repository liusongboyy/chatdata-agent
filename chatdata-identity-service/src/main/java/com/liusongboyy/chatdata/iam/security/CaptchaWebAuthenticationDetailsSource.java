package com.liusongboyy.chatdata.iam.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationDetailsSource;

/** 让表单登录在构造 Authentication 时带上验证码字段。 */
public class CaptchaWebAuthenticationDetailsSource
        implements AuthenticationDetailsSource<HttpServletRequest, CaptchaWebAuthenticationDetails> {

    @Override
    public CaptchaWebAuthenticationDetails buildDetails(HttpServletRequest context) {
        return new CaptchaWebAuthenticationDetails(context);
    }
}
