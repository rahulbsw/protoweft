package io.agentweave.examples.argi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * An ARGI {@code ReactAgent} that calls AgentWeave tools ({@link BankTools}), offered per request
 * by the capability catalog, and is served as an A2A agent at {@code /a2a}. Transfers need the
 * caller's approval, which A2A carries as {@code input-required}. The wiring is in
 * {@link AgentConfiguration}.
 */
@SpringBootApplication
public class ArgiExample {

    public static void main(String[] args) {
        SpringApplication.run(ArgiExample.class, args);
    }
}
