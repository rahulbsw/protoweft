package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ProtocolId;

import java.util.List;
import java.util.Set;

/**
 * Feature identifiers used in the RAP client's and server's {@link ComplianceReport}s. The
 * supported and unsupported sets are the module's honest coverage claim and are asserted by
 * tests; client and server use the same ids.
 */
public final class RapFeatures {

    /**
     * The wire revision: RAP has no version field, so the revision is the Hydro Infinity commit the
     * implementation follows.
     */
    public static final String WIRE_REVISION = "infinity@e1a3e4b";

    public static final String TOOLSET_DISCOVERY = "toolset/discovery";
    /** {@code displayScript}: frontend-only Rhai scripts, ignored. */
    public static final String TOOLSET_DISPLAY_SCRIPT = "toolset/display_script";
    public static final String TOOL_INVOCATION = "tool/invocation";
    public static final String TOOL_RESULT_TEXT = "tool_result/text";
    public static final String TOOL_RESULT_CONTENT = "tool_result/content";
    /** {@code display_as}: UI-only display segments, dropped. */
    public static final String TOOL_RESULT_DISPLAY_AS = "tool_result/display_as";
    public static final String SUBSCRIPTION_EVENTS = "subscription_event";
    public static final String OAUTH = "oauth";
    public static final String USER_CHOICE = "user_choice";
    public static final String TOOL_CANCELLATION = "tool_cancellation";
    public static final String THREAD_CLOSURE = "thread_closure";
    public static final String VIEW_UPDATE = "view_update";
    public static final String MIGRATION = "migration";

    static final Set<String> SUPPORTED = Set.of(TOOLSET_DISCOVERY, TOOL_INVOCATION, TOOL_RESULT_TEXT,
            TOOL_RESULT_CONTENT, SUBSCRIPTION_EVENTS, OAUTH, USER_CHOICE, TOOL_CANCELLATION, THREAD_CLOSURE);

    static final Set<String> UNSUPPORTED = Set.of(TOOLSET_DISPLAY_SCRIPT, TOOL_RESULT_DISPLAY_AS, VIEW_UPDATE,
            MIGRATION);

    private RapFeatures() {
    }

    static ComplianceReport report() {
        return new ComplianceReport(ProtocolId.RAP, "none", ModuleVersion.VALUE, List.of(WIRE_REVISION), null,
                SUPPORTED, UNSUPPORTED);
    }
}
