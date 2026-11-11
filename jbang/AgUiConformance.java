///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS io.github.rahulbsw:protoweft-bom:${protoweft.version:0.1.0}@pom
//DEPS io.github.rahulbsw:protoweft-agui

/**
 * Checks any AG-UI agent against the producer rules of AG-UI 1.0:
 * {@code jbang agui-conformance@rahulbsw/protoweft <agent-url> [--bearer TOKEN] [--header Name:Value]
 * [--message TEXT] [--timeout SECONDS] [--json]}.
 */
public class AgUiConformance {
    public static void main(String[] args) {
        io.github.rahulbsw.protoweft.agui.AgUiConformance.main(args);
    }
}
