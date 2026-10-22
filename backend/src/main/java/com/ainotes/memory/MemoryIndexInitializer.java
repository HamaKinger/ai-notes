package com.ainotes.memory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 启动时确保长期记忆索引存在(mapping 来自 {@code classpath:elasticsearch/memory-index.json})。
 *
 * <p>与 {@link com.ainotes.ingest.EsIndexInitializer} 同一套约定:索引已存在就不动;ES 不可用只告警,
 * 不影响启动 —— 此时记忆写不进去、也召回不到,但笔记与对话照常。
 */
@Component
public class MemoryIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexInitializer.class);
    private static final String MAPPING_RESOURCE = "elasticsearch/memory-index.json";

    private final MemoryIndexStore store;

    public MemoryIndexInitializer(MemoryIndexStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (store.indexExists()) {
                log.info("记忆索引 {} 已存在,跳过创建", store.indexName());
                return;
            }
            store.createIndex(readMapping());
            log.info("已创建记忆索引 {}", store.indexName());
        } catch (Exception e) {
            log.warn("记忆索引初始化失败,长期记忆暂不可用: {}", e.getMessage());
        }
    }

    private static String readMapping() throws Exception {
        ClassPathResource resource = new ClassPathResource(MAPPING_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
