package io.github.rahulbsw.protoweft.spring.boot;

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
 * Actuator integration, when present: health {@code protoweft} (connector states) and info
 * {@code protoweft} (connector compliance reports).
 */
@AutoConfiguration(after = ProtoweftAutoConfiguration.class)
@ConditionalOnBean(ProtoweftConnectors.class)
public class ProtoweftActuatorAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnEnabledHealthIndicator("protoweft")
    static class HealthConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "protoweftHealthIndicator")
        ProtoweftHealthIndicator protoweftHealthIndicator(ProtoweftConnectors connectors) {
            return new ProtoweftHealthIndicator(connectors);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({InfoContributor.class, ConditionalOnEnabledInfoContributor.class})
    @ConditionalOnEnabledInfoContributor(value = "protoweft", fallback = InfoContributorFallback.USE_DEFAULTS_PROPERTY)
    static class InfoConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "protoweftInfoContributor")
        ProtoweftInfoContributor protoweftInfoContributor(ProtoweftConnectors connectors) {
            return new ProtoweftInfoContributor(connectors);
        }
    }
}
