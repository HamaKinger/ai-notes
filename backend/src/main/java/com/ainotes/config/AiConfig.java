package com.ainotes.config;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring AI 装配。
 *
 * <p>两个 {@link ChatClient}:
 * <ul>
 *   <li>{@code chatClient}({@code @Primary})—— 对话与文本工具用,保持原样;</li>
 *   <li>{@code agentChatClient} —— <b>Agent 专用</b>。它在建的时候就关掉了 ChatClient 自动注册的
 *       {@code ToolCallingAdvisor}:那玩意儿会在一次请求里把"调用工具 → 再问模型"整个跑完,
 *       而 Agent 的写操作**必须停在中间**等用户确认,还得把每一步落库、发事件。所以工具循环
 *       由 {@code AgentService} 自己写,模型层只负责"要么给正文、要么给工具调用"。</li>
 * </ul>
 *
 * <p>为什么要分成两个 bean 而不是在每次请求上关:自动注册发生在建 ChatClient 的时候,
 * 分开建可以把"Agent 关掉自动工具循环"这句话落到装配处 —— 将来谁改了 Agent 的提示词或工具,
 * 都不会顺手把这条关掉的开关又打开。
 */
@Configuration
public class AiConfig {

    @Bean
    @Primary
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    ChatClient agentChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .build();
    }
}
