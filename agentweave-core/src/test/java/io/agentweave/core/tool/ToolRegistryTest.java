package io.agentweave.core.tool;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.server.CallerIdentity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private static LocalTools echo(String prefix) {
        return LocalTools.builder()
                .tool("echo", "Echo", Map.of("type", "object"),
                        (args, caller) -> ToolOutcome.text(prefix + args.get("m") + "@" + caller.name()))
                .build();
    }

    @Test
    void namespacesToolsBySource() {
        ToolRegistry registry = new ToolRegistry().register("a", echo("A:")).register("b", echo("B:"));

        assertThat(registry.listTools()).extracting(ToolSpec::name).containsExactly("a.echo", "b.echo");
        assertThat(registry.invoke(ToolInvocation.of("b.echo", Map.of("m", "hi")), CallerIdentity.of("bob")).text())
                .isEqualTo("B:hi@bob");
    }

    @Test
    void unknownSourceOrMissingPrefixIsToolNotFound() {
        ToolRegistry registry = new ToolRegistry().register("a", echo(""));
        for (String name : new String[]{"echo", "zzz.echo", ".echo"}) {
            assertThatThrownBy(() -> registry.invoke(ToolInvocation.of(name, Map.of())))
                    .isInstanceOfSatisfying(AgentWeaveException.class,
                            e -> assertThat(e.category()).isEqualTo(ErrorCategory.TOOL_NOT_FOUND));
        }
    }

    @Test
    void rejectsInvalidAndDuplicateSourceNames() {
        ToolRegistry registry = new ToolRegistry().register("a", echo(""));
        assertThatThrownBy(() -> registry.register("a", echo(""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register("a.b", echo(""))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeClosesCloseableSources() {
        AtomicBoolean closed = new AtomicBoolean();
        class Closeable implements ToolSource, AutoCloseable {
            public java.util.List<ToolSpec> listTools() {
                return java.util.List.of();
            }

            public ToolOutcome invoke(ToolInvocation invocation) {
                return null;
            }

            public void close() {
                closed.set(true);
            }
        }
        new ToolRegistry().register("c", new Closeable()).close();
        assertThat(closed).isTrue();
    }
}
