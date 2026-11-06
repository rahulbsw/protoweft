package com.example.tools;

import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/** Every @AgentTool method on a Spring bean becomes an MCP tool. Replace these with your own. */
@Component
public class GreetingTools {

    @AgentTool(description = "Greets someone by name", readOnly = true)
    public String greet(@ToolParam(description = "Who to greet") String name) {
        return "Hello, " + name + "!";
    }

    public record Sum(long total) {
    }

    @AgentTool(description = "Adds two integers", readOnly = true)
    public Sum add(long a, long b) {
        return new Sum(a + b);
    }
}
