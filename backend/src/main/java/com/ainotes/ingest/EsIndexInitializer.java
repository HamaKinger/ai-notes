package com.ainotes.ingest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 启动时确保 Elasticsearch 索引存在。
 *
 * <p>索引 mapping 的唯一来源是 {@code classpath:elasticsearch/chunk-index.json},里面配置了
 * IK 中文分词器与 1024 维 HNSW 向量字段。刻意不使用 Spring AI 的
 * {@code initialize-schema}(见 {@code application.yml}),否则会覆盖掉 IK 配置。
 *
 * <p>Elasticsearch 不可用时只告警不启动失败:笔记功能依然可用,只是检索为空。
 */
@Component
public class EsIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EsIndexInitializer.class);
    private static final String MAPPING_RESOURCE = "elasticsearch/chunk-index.json";

    private final ChunkIndexStore store;

    public EsIndexInitializer(ChunkIndexStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (store.indexExists()) {
                log.info("Elasticsearch 索引 {} 已存在,跳过创建", store.indexName());
                return;
            }
            String mapping = readMapping();
            try {
                store.createIndex(mapping);
                log.info("已创建 Elasticsearch 索引 {}(IK 中文分词)", store.indexName());
            } catch (RuntimeException createFailed) {
                if (!isMissingIkTokenizer(createFailed)) {
                    throw createFailed;
                }
                // 很多 ES 镜像(包括本项目 deploy/docker-compose.yml 里的官方镜像)**没有预装
                // analysis-ik**,自定义分析器 cn/cn_search 就建不出来。此时整体回退到内置
                // standard 分词器:中文按单字切分,召回尚可、精度下降,但字段名与类型完全一致,
                // 检索 DSL 不用改。装上 IK 后删掉索引重启即可自动切回。
                log.warn("IK 分词器不可用,回退到内置 standard 分词器(中文检索精度会下降): {}",
                        createFailed.getMessage());
                store.createIndex(toStandardAnalyzer(mapping));
                log.info("已创建 Elasticsearch 索引 {}(standard 分词回退)", store.indexName());
            }
        } catch (Exception e) {
            log.warn("Elasticsearch 索引初始化失败,检索功能暂不可用: {}", e.getMessage());
        }
    }

    /** 只有"缺 IK 分词器"才值得回退,其它建索引错误应该照实抛出去。 */
    private static boolean isMissingIkTokenizer(RuntimeException e) {
        String message = e.getMessage();
        return message != null && message.contains("ik_");
    }

    /** 把 mapping 里的 IK 分词器替换为 ES 内置的 standard。 */
    private static String toStandardAnalyzer(String mapping) {
        return mapping
                .replace("\"ik_max_word\"", "\"standard\"")
                .replace("\"ik_smart\"", "\"standard\"");
    }

    private static String readMapping() throws Exception {
        ClassPathResource resource = new ClassPathResource(MAPPING_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
