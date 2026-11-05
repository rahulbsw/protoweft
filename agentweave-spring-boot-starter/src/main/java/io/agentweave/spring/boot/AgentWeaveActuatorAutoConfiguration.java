package io.agentweave.spring.boot;

import org.springframework.boot.actuate.autoconfigure.info.ConditionalOnEnabledInfoContributor;
import org.springframework.boot.actuate.autoconfigure.info.InfoContributorFallback;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Actuator integration, when present: health {@code agentweave} (connector states) and info
 * {@code agentweave} (connector compliance reports).
 */
@AutoConfiguration(after = AgentWeaveAutoConfiguration.class)
@ConditionalOnBean(AgentWeaveConnectors.class)
public class AgentWeaveActuatorAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnEnabledHealthIndicator("agentweave")
    static class HealthConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "agentweaveHealthIndicator")
        AgentWeaveHealthIndicator agentweaveHealthIndicator(AgentWeaveConnectors connectors) {
            return new AgentWeaveHealthIndicator(connectors);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({InfoContributor.class, ConditionalOnEnabledInfoContributor.class})
    @ConditionalOnEnabledInfoContributor(value = "agentweave", fallback = InfoContributorFallback.USE_DEFAULTS_PROPERTY)
    static class InfoConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "agentweaveInfoContributor")
        AgentWeaveInfoContributor agentweaveInfoContributor(AgentWeaveConnectors connectors) {
            return new AgentWeaveInfoContributor(connectors);
        }
    }
}
