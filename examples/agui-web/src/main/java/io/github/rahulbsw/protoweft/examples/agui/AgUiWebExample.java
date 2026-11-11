package io.github.rahulbsw.protoweft.examples.agui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Serves a Spring AI agent over AG-UI at {@code /agui} and the browser page at {@code /}. */
@SpringBootApplication
public class AgUiWebExample {

    public static void main(String[] args) {
        SpringApplication.run(AgUiWebExample.class, args);
    }
}
