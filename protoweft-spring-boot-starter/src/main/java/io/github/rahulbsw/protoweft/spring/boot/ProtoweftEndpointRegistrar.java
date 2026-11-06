package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.server.InboundAuthFilter;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Mounts one protocol server's {@link ServletEndpoint}s in the Spring Boot servlet container:
 * a {@link ServletRegistrationBean} per endpoint, guarded by an {@link InboundAuthFilter}
 * {@link FilterRegistrationBean} on the same URL patterns, both async when the endpoint is.
 *
 * <p>Its order is {@code protoweft.server.auth-filter-order}: Boot registers initializers in
 * order, so with the default (0) the auth filters sit after Spring Security's filter chain (-100).
 */
public final class ProtoweftEndpointRegistrar implements ServletContextInitializer, Ordered {

    private final List<ServletRegistrationBean<?>> servlets = new ArrayList<>();
    private final List<FilterRegistrationBean<?>> filters = new ArrayList<>();
    private final int order;

    ProtoweftEndpointRegistrar(List<ServletEndpoint> endpoints, InboundAuthenticator authenticator, int order) {
        this.order = order;
        for (ServletEndpoint endpoint : endpoints) {
            String[] patterns = endpoint.urlPatterns().toArray(String[]::new);
            ServletRegistrationBean<?> servlet = new ServletRegistrationBean<>(endpoint.servlet(), false, patterns);
            servlet.setName("protoweft-" + endpoint.name());
            servlet.setAsyncSupported(endpoint.asyncSupported());
            servlet.setOrder(order);
            FilterRegistrationBean<InboundAuthFilter> filter =
                    new FilterRegistrationBean<>(new InboundAuthFilter(authenticator, endpoint.protocol()));
            filter.setName("protoweft-" + endpoint.name() + "-auth");
            filter.setUrlPatterns(endpoint.urlPatterns());
            filter.setAsyncSupported(endpoint.asyncSupported());
            filter.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
            filter.setOrder(order);
            servlets.add(servlet);
            filters.add(filter);
        }
    }

    /** The servlet registrations, one per endpoint. */
    public List<ServletRegistrationBean<?>> servlets() {
        return List.copyOf(servlets);
    }

    /** The inbound-auth filter registrations, one per endpoint. */
    public List<FilterRegistrationBean<?>> filters() {
        return List.copyOf(filters);
    }

    @Override
    public void onStartup(ServletContext servletContext) throws ServletException {
        for (int i = 0; i < servlets.size(); i++) {
            servlets.get(i).onStartup(servletContext);
            filters.get(i).onStartup(servletContext);
        }
    }

    @Override
    public int getOrder() {
        return order;
    }
}
