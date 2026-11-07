package io.github.rahulbsw.protoweft.examples.gateway;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the example and checks its output, which apart from the port is the same on every run. */
class GatewayExampleTest {

    @Test
    void servesAndConsumesTheToolOverEveryProtocol() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            GatewayExample.main(new String[0]);
        } finally {
            System.setOut(original);
        }
        String out = captured.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        String forecast = "{\"city\":\"Oslo\",\"celsius\":21.5,\"summary\":\"Sunny\"}";
        assertThat(out).contains(
                "  - via_rap.forecast: Weather forecast for a city",
                "  - via_rap.updates: Streams forecast updates for a city",
                "  via_mcp.forecast   -> " + forecast,
                "  via_utcp.forecast  -> " + forecast,
                "  via_a2a.ask        -> " + forecast,
                "  via_rap.forecast   -> " + forecast,
                """
                        RAP subscription:
                          via_rap.updates    -> Watching Oslo
                            event: Oslo: 22.0 C, sunny
                            event: Oslo: 19.5 C, showers (associative)
                            event: Oslo: no more updates (final)
                        """,
                "  rap   sdk=none ");
        assertThat(out).contains("wire=[infinity@e1a3e4b] negotiated=infinity@e1a3e4b");
    }
}
