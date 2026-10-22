package com.ainotes.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Response;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.Test;

/**
 * 守住一个真实踩过的坑:低层 {@link Rest5Client#performRequest} 对 4xx/5xx
 * **既不抛异常也不返回 null**,状态码只在 {@link Response} 里。曾经因为不判状态码,
 * 建索引被 ES 拒绝(400)时日志照样打"已创建",索引其实不存在,所有检索静默返回空。
 *
 * <p>这里用 Mockito 伪造 Response,不需要真的连 Elasticsearch。
 */
class ChunkIndexStoreStatusTest {

    private static Response response(int status, String body) throws Exception {
        Response response = mock(Response.class);
        when(response.getStatusCode()).thenReturn(status);
        if (body != null) {
            HttpEntity entity = mock(HttpEntity.class);
            when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            when(response.getEntity()).thenReturn(entity);
        }
        return response;
    }

    private static Rest5Client clientReturning(Response response) throws Exception {
        Rest5Client client = mock(Rest5Client.class);
        when(client.performRequest(any(Request.class))).thenReturn(response);
        return client;
    }

    @Test
    void createIndexFailsLoudlyWhenElasticsearchRejectsTheMapping() throws Exception {
        String errorBody = "{\"error\":{\"reason\":\"Custom Analyzer [cn_search] failed to find "
                + "tokenizer under name [ik_smart]\"},\"status\":400}";
        ChunkIndexStore store = new ChunkIndexStore(clientReturning(response(400, errorBody)), "ai-notes-chunk");

        assertThatThrownBy(() -> store.createIndex("{\"mappings\":{}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("ik_smart");
    }

    @Test
    void createIndexSucceedsOnAcceptedMapping() throws Exception {
        ChunkIndexStore store = new ChunkIndexStore(
                clientReturning(response(200, "{\"acknowledged\":true}")), "ai-notes-chunk");

        assertThatCode(() -> store.createIndex("{\"mappings\":{}}")).doesNotThrowAnyException();
    }

    @Test
    void indexExistsIsFalseOnNotFoundInsteadOfBlowingUp() throws Exception {
        ChunkIndexStore store = new ChunkIndexStore(
                clientReturning(response(404, "{\"error\":\"index_not_found_exception\"}")), "ai-notes-chunk");

        assertThat(store.indexExists()).isFalse();
    }

    @Test
    void indexExistsIsTrueOnOk() throws Exception {
        ChunkIndexStore store = new ChunkIndexStore(clientReturning(response(200, null)), "ai-notes-chunk");

        assertThat(store.indexExists()).isTrue();
    }
}
