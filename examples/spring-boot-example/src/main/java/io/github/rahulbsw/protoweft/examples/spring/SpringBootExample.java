package io.github.rahulbsw.protoweft.examples.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Serves {@link WeatherTools} over MCP ({@code /mcp}) and UTCP ({@code /utcp}), and
 * {@link WeatherAgent} over A2A ({@code /a2a}), all behind {@link TokenAuthenticator}.
 * Everything Protoweft-specific is configured in {@code application.yml}.
 */
@SpringBootApplication
public class SpringBootExample {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootExample.class, args);
    }
}
