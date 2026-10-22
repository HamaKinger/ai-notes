package com.ainotes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ai-notes:以本地知识库为长期记忆的 AI Agent。
 */
@SpringBootApplication
@EnableAsync
public class AiNotesApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiNotesApplication.class, args);
    }
}
