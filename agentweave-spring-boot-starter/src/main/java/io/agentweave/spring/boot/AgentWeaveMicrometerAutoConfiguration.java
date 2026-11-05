package io.agentweave.spring.boot;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Bridges AgentWeave observations to Micrometer when an {@link ObservationRegistry} bean exists.
 * The bridge is one more {@code ObservationHandler} bean, combined with any of the application's own.
 */
@AutoConfiguration(before = AgentWeaveAutoConfiguration.class,
        afterName = "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "agentweave.observations", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentWeaveMicrometerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MicrometerObservationHandler agentWeaveMicrometerObservationHandler(ObservationRegistry registry) {
        return new MicrometerObservationHandler(registry);
    }
}
