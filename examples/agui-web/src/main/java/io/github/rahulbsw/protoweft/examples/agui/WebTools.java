package io.github.rahulbsw.protoweft.examples.agui;

import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** A local tool (source {@code local}); its calls show up in the page as tool-call cards. */
@Component
public class WebTools {

    @AgentTool(description = "Current temperature in a city, in degrees Celsius", readOnly = true)
    public double temperature(@ToolParam(description = "City name") String city) {
        return 12 + Math.floorMod(city.toLowerCase(Locale.ROOT).hashCode(), 15);
    }
}
