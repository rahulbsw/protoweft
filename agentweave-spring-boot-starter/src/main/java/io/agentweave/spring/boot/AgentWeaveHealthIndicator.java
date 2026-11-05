package io.agentweave.spring.boot;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports each declared connector's lifecycle state (it does not probe peers): DOWN if any
 * failed to start, OUT_OF_SERVICE while any is still pending, otherwise UP.
 */
public final class AgentWeaveHealthIndicator implements HealthIndicator {

    private final AgentWeaveConnectors connectors;

    public AgentWeaveHealthIndicator(AgentWeaveConnectors connectors) {
        this.connectors = connectors;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean failed = false;
        boolean pending = false;
        for (AgentWeaveConnectors.Status s : connectors.status().values()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("protocol", s.protocol().value());
            detail.put("state", s.state().name());
            if (s.failure() != null) {
                detail.put("error", String.valueOf(s.failure().getMessage()));
            }
            details.put(s.name(), detail);
            failed |= s.state() == AgentWeaveConnectors.State.FAILED;
            pending |= s.state() == AgentWeaveConnectors.State.PENDING;
        }
        Health.Builder health = failed ? Health.down() : pending ? Health.outOfService() : Health.up();
        return health.withDetails(details).build();
    }
}
