package io.github.rahulbsw.protoweft.discovery;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.BufferedToolSubscription;
import io.github.rahulbsw.protoweft.core.tool.ToolContext;
import io.github.rahulbsw.protoweft.core.tool.ToolContexts;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogPassThroughTest {

    interface MarkerContext extends ToolContext {
    }

    @Test
    void contextsReachTheToolAndSubscriptionsComeBackUntouched() {
        AtomicReference<ToolContexts> seen = new AtomicReference<>();
        BufferedToolSubscription subscription = new BufferedToolSubscription("sub-1", () -> { });
        ToolSource source = new ToolSource() {
            @Override
            public List<ToolSpec> listTools() {
                return List.of(new ToolSpec("watch", "Watch prices for a symbol", Map.of("type", "object")));
            }

            @Override
            public ToolOutcome invoke(ToolInvocation invocation) {
                throw new AssertionError("the context-aware overload must be used");
            }

            @Override
            public ToolOutcome invoke(ToolInvocation invocation, CallerIdentity caller, ToolProgress progress,
                                      ToolContexts contexts) {
                seen.set(contexts);
                return ToolOutcome.text("watching").withSubscription(subscription);
            }
        };
        CapabilityCatalog catalog = CapabilityCatalog.builder().source("prices", source).build();
        MarkerContext marker = new MarkerContext() {
        };
        ToolContexts contexts = ToolContexts.of(MarkerContext.class, marker);

        ToolOutcome viaCatalog = catalog.invoke("prices.watch", Map.of(), CallerIdentity.ANONYMOUS, ToolProgress.NOOP,
                contexts);
        assertThat(seen.get()).isSameAs(contexts);
        assertThat(viaCatalog.subscription()).containsSame(subscription);

        seen.set(null);
        ToolOutcome viaMetaTool = DiscoveryTools.of(catalog).invoke(ToolInvocation.of(DiscoveryTools.CALL,
                Map.of("name", "prices.watch", "arguments", Map.of())), CallerIdentity.ANONYMOUS, ToolProgress.NOOP, contexts);
        assertThat(seen.get()).isSameAs(contexts);
        assertThat(viaMetaTool.subscription()).containsSame(subscription);
    }
}
