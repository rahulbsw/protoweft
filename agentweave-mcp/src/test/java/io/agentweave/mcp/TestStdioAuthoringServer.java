package io.agentweave.mcp;

/** Serves {@link TestAuthoring} over stdio; launched as a subprocess by the tests. */
public final class TestStdioAuthoringServer {

    public static void main(String[] args) {
        TestAuthoring.Fixture fixture = TestAuthoring.create();
        McpToolServer.stdio(fixture.tools()).serverInfo("agentweave-stdio-authoring", "1.0.0")
                .resources(fixture.resources()).prompts(fixture.prompts()).run();
    }
}
