package com.ainotes.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置。
 *
 * <p>始终启用 Bearer Token(JWT)解析:带了合法 Token 的请求会解析出当前用户。
 * {@code ai-notes.security.dev-permit-all} 只决定"没带 Token 时是否放行":
 * <ul>
 *   <li>false(默认):除 {@code /api/v1/auth/**} 与健康检查外,一律要求认证 ——
 *       匿名请求得到 401,服务端才是真正的访问控制边界。</li>
 *   <li>true:放行所有请求,未登录时业务层回落到演示账号。仅供本地调试
 *       (想直接用 curl 打接口、不想先换 Token 时显式置 true)。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final boolean devPermitAll;

    public SecurityConfig(@Value("${ai-notes.security.dev-permit-all:false}") boolean devPermitAll) {
        this.devPermitAll = devPermitAll;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        if (devPermitAll) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/**", "/actuator/health", "/actuator/info").permitAll()
                    .anyRequest().authenticated());
        }

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
