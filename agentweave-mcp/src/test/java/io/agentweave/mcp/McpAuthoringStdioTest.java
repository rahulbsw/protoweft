package io.agentweave.mcp;

import org.junit.jupiter.api.AfterEach;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * {@link AuthoringContract} over stdio: our client against {@link TestStdioAuthoringServer} in
 * a subprocess without the servlet API on its classpath.
 */
class McpAuthoringStdioTest extends AuthoringContract {

    private McpClientConnector connector;

    @AfterEach
    void close() {
        if (connector != null) {
            connector.close();
        }
    }

    @Override
    McpClientConnector connector() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Arrays.stream(System.getProperty("agentweave.test.classpath").split(File.pathSeparator))
                .filter(entry -> !entry.contains("jakarta.servlet-api"))
                .collect(Collectors.joining(File.pathSeparator));
        connector = McpClientConnector.stdio(java, "-cp", classpath, TestStdioAuthoringServer.class.getName())
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        connector.start();
        return connector;
    }

    @Override
    String expectedCaller() {
        return "anonymous";
    }
}
