package io.agentweave.utcp;

import io.agentweave.core.server.AuthDecision;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.server.jetty.AgentWeaveServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional interop check: the official Python client ({@code pip install utcp utcp-http}) against
 * our provider. Runs only when {@code UTCP_PYTHON} names a Python with those packages installed.
 */
@EnabledIfEnvironmentVariable(named = "UTCP_PYTHON", matches = ".+")
class PythonInteropTest {

    private static final String SCRIPT = """
            import asyncio, json, sys
            from utcp.utcp_client import UtcpClient

            async def main():
                client = await UtcpClient.create(config={
                    "variables": {"agentweave_API_KEY": "s3cret"},
                    "manual_call_templates": [{
                        "name": "agentweave", "call_template_type": "http", "url": sys.argv[1],
                        "auth": {"auth_type": "api_key", "api_key": "${API_KEY}", "var_name": "X-Api-Key"}}]})
                out = {"tools": sorted(t.name for t in await client.search_tools("", limit=100))}
                out["add"] = await client.call_tool("agentweave.add", {"body": {"a": 2, "b": 3}})
                out["forecast"] = await client.call_tool("agentweave.forecast", {"body": {"city": "Oslo"}})
                out["whoami"] = await client.call_tool("agentweave.whoami", {"body": {}})
                try:
                    await client.call_tool("agentweave.fail", {"body": {}})
                except Exception as e:
                    out["fail"] = getattr(e, "message", str(e))
                print(json.dumps(out))

            asyncio.run(main())
            """;

    @TempDir
    Path dir;

    @Test
    void theReferencePythonClientUsesOurManual() throws Exception {
        LocalTools tools = LocalTools.builder().scan(new ProviderRoundTripTest.Tools()).build();
        try (AgentWeaveServer server = AgentWeaveServer.builder().port(0)
                .endpoints(UtcpManualProvider.builder(tools).apiKeyAuth("X-Api-Key", "API_KEY").build())
                .authenticator(req -> req.header("X-Api-Key").filter("s3cret"::equals).isPresent()
                        ? AuthDecision.allow(CallerIdentity.of("py")) : AuthDecision.unauthenticated("no key"))
                .start()) {
            Path script = Files.writeString(dir.resolve("client.py"), SCRIPT);
            Process p = new ProcessBuilder(System.getenv("UTCP_PYTHON"), script.toString(), server.baseUrl() + "/utcp")
                    .redirectError(dir.resolve("stderr.txt").toFile())
                    .start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).as(Files.readString(dir.resolve("stderr.txt"))).isZero();

            String[] lines = stdout.split("\\R");
            Map<String, Object> out = Json.object(Json.parse(lines[lines.length - 1]));
            assertThat(out.get("tools")).isEqualTo(List.of("agentweave.add", "agentweave.fail", "agentweave.forecast",
                    "agentweave.whoami"));
            assertThat(out.get("add")).isEqualTo("5");
            assertThat(out.get("forecast")).isEqualTo(Map.of("city", "Oslo", "celsius", 21));
            assertThat(out.get("whoami")).isEqualTo("py");
            // aiohttp shows the server's reason phrase (Jetty sends "Server Error" for 500).
            assertThat(out.get("fail")).asString().endsWith("Server Error: boom");
        }
    }
}
