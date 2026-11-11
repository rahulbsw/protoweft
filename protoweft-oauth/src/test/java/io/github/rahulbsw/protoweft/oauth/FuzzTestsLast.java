package io.github.rahulbsw.protoweft.oauth;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

import java.util.Comparator;

/**
 * Runs the {@code *FuzzTest} classes after every other test class (configured in
 * {@code junit-platform.properties}). The first {@code @FuzzTest} installs Jazzer's agent, which
 * rewrites classes loaded afterwards and drops their parameter names, which {@code LocalTools}
 * needs; tests that ran earlier are unaffected.
 */
public final class FuzzTestsLast implements ClassOrderer {

    @Override
    public void orderClasses(ClassOrdererContext context) {
        context.getClassDescriptors()
                .sort(Comparator.comparing(d -> d.getTestClass().getSimpleName().endsWith("FuzzTest")));
    }
}
