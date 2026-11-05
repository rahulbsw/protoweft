package io.agentweave.examples.springai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A Spring AI {@code ChatClient} that calls AgentWeave tools ({@link AssistantTools}, plus any
 * MCP, UTCP or A2A sources configured under {@code agentweave.*}) and is served as an A2A agent
 * at {@code /a2a}. The beans that connect the two are in {@link AssistantConfiguration}.
 */
@SpringBootApplication
public class SpringAiExample {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiExample.class, args);
    }
}
