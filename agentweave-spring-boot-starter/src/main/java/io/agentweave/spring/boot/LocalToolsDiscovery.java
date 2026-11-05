package io.agentweave.spring.boot;

import io.agentweave.core.tool.AgentTool;
import io.agentweave.core.tool.LocalTools;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/** Collects every singleton bean with {@link AgentTool} methods into one {@link LocalTools}. */
final class LocalToolsDiscovery {

    private LocalToolsDiscovery() {
    }

    static LocalTools discover(ListableBeanFactory beans, List<LocalTools.LocalTool> extra) {
        LocalTools.Builder builder = LocalTools.builder();
        String[] names = beans.getBeanNamesForType(Object.class, false, false);
        Arrays.sort(names);
        for (String name : names) {
            Class<?> type = beans.getType(name, false);
            if (type == null || !hasTools(ClassUtils.getUserClass(type))) {
                continue;
            }
            builder.scan(target(name, beans.getBean(name)));
        }
        builder.addAll(extra);
        return builder.build();
    }

    private static boolean hasTools(Class<?> type) {
        for (Method m : type.getMethods()) {
            if (m.isAnnotationPresent(AgentTool.class) && !Modifier.isStatic(m.getModifiers())) {
                return true;
            }
        }
        return false;
    }

    /** LocalTools reads annotations from the object's class, so AOP proxies are unwrapped (advice is bypassed). */
    private static Object target(String name, Object bean) {
        if (!AopUtils.isAopProxy(bean)) {
            return bean;
        }
        Object target = AopProxyUtils.getSingletonTarget(bean);
        if (target == null) {
            throw new IllegalStateException("bean '" + name + "' has @AgentTool methods but is an AOP proxy "
                    + "without a singleton target; expose the tools from a plain bean");
        }
        return target;
    }
}
