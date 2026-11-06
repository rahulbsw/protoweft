package io.github.rahulbsw.protoweft.spring.boot;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Bridges Protoweft observations to Micrometer when an {@link ObservationRegistry} bean exists.
 * The bridge is one more {@code ObservationHandler} bean, combined with any of the application's own.
 */
@AutoConfiguration(before = ProtoweftAutoConfiguration.class,
        afterName = "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "protoweft.observations", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProtoweftMicrometerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MicrometerObservationHandler protoweftMicrometerObservationHandler(ObservationRegistry registry) {
        return new MicrometerObservationHandler(registry);
    }
}
