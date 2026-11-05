package io.agentweave.discovery;

import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Smoke test for the in-memory sizing target (about 5,000 capabilities). */
class CatalogScaleTest {

    private static final String[] WORDS = ("account address alert archive balance bucket calendar cart channel "
            + "comment contact contract customer dataset deploy device document email event expense file flight "
            + "folder forecast invoice issue job ledger meeting message metric note order payment pipeline policy "
            + "project query record report repository reservation schedule secret server shipment stock task "
            + "ticket token user video weather workflow").split(" ");
    private static final String[] VERBS = "get list create update delete search export import sync close".split(" ");

    @Test
    void searchesFiveThousandCapabilitiesQuickly() {
        Random random = new Random(42);
        CapabilityCatalog.Builder builder = CapabilityCatalog.builder();
        int perSource = 100;
        for (int s = 0; s < 50; s++) {
            LocalTools.Builder tools = LocalTools.builder();
            for (int t = 0; t < perSource; t++) {
                String verb = VERBS[random.nextInt(VERBS.length)];
                String noun = WORDS[random.nextInt(WORDS.length)];
                String other = WORDS[random.nextInt(WORDS.length)];
                tools.tool(verb + "_" + noun + "_" + t, verb + " a " + noun + " by " + other + ".",
                        Map.of("type", "object", "properties", Map.of(other, Map.of("type", "string"))),
                        (args, caller) -> ToolOutcome.text("ok"));
            }
            builder.source("svc" + s, tools.build());
        }
        CapabilityCatalog catalog = builder.build();
        assertThat(catalog.size()).isEqualTo(5_000);

        List<String> queries = List.of("export the invoice", "list open tickets", "delete a user account",
                "weather forecast for tomorrow", "sync calendar events");
        long[] nanos = new long[25];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            catalog.search(DiscoveryQuery.builder(queries.get(i % queries.size())).build());
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        double medianMs = nanos[nanos.length / 2] / 1e6;
        System.out.printf("5,000-capability keyword search median: %.2f ms%n", medianMs);
        assertThat(medianMs).as("lenient bound; logged for tracking").isLessThan(200);
    }
}
