package com.liusongboyy.chatdata.iam.web;

import com.liusongboyy.chatdata.iam.security.IamUserDetailsService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 当前登录主体。P0 用会话鉴权，供管理页与联调使用。
 *
 * <p>认证对象里只有用户名，组织与 userId 现查——见 {@code IamUserDetails} 的说明。
 */
@RestController
public class MeController {

    private final IamUserDetailsService userDetailsService;

    public MeController(IamUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @GetMapping("/iam/me")
    public Map<String, Object> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Map.of("authenticated", false);
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>(
                AuthController.principalOf(userDetailsService.loadPrincipal(authentication.getName())));
        body.put("authenticated", true);
        return body;
    }
}
