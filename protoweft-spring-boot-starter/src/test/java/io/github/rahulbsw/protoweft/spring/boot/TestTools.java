package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;

/** {@code @AgentTool} methods used by the tests. */
public class TestTools {

    @AgentTool(description = "Adds two integers")
    public long add(@ToolParam(description = "first") long a, @ToolParam(description = "second") long b) {
        return a + b;
    }

    @AgentTool(description = "Names the authenticated caller")
    public String whoami(CallerIdentity caller) {
        return caller.name();
    }
}
