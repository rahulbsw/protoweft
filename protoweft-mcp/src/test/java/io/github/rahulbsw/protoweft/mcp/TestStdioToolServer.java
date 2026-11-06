package io.github.rahulbsw.protoweft.mcp;

/** Runs {@link TestTools} as an stdio MCP server; launched as a subprocess by the tests. */
public final class TestStdioToolServer {

    public static void main(String[] args) {
        McpToolServer.stdio(TestTools.create()).serverInfo("protoweft-stdio-test", "1.0.0").run();
    }
}
