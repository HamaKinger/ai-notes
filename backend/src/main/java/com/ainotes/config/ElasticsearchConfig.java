package com.ainotes.config;

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端。
 *
 * <p>Spring AI 2.0 的 ES 向量库基于 Elasticsearch Java Client 9.x
 * ({@code co.elastic.clients:elasticsearch-java} + {@code elasticsearch-rest5-client}),
 * 低层客户端类型是 {@link Rest5Client}。
 *
 * <p>注意:本项目没有引入 Spring Boot 的 ES data starter,所以没有自动配置,
 * 这里显式创建 Bean。上层的索引/检索操作都走 {@link Rest5Client#performRequest} 的
 * 原始 JSON 调用,这样可以 1:1 使用设计文档里的查询 DSL(含 rrf retriever)。
 */
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    Rest5Client rest5Client(
            @Value("${spring.elasticsearch.uris:http://localhost:9200}") List<String> uris,
            @Value("${spring.elasticsearch.username:}") String username,
            @Value("${spring.elasticsearch.password:}") String password) {
        URI[] endpoints = uris.stream().map(URI::create).toArray(URI[]::new);
        var builder = Rest5Client.builder(endpoints);
        if (username != null && !username.isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.setDefaultHeaders(new Header[]{
                    new BasicHeader("Authorization", "Basic " + token)
            });
        }
        return builder.build();
    }
}
