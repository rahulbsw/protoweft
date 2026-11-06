package io.github.rahulbsw.protoweft.examples.springai;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Plain bean methods the Protoweft starter registers as local tools (source {@code local}), so
 * the model sees them as {@code local__temperature}, {@code local__time} and {@code local__whoami}.
 */
@Component
public class AssistantTools {

    @AgentTool(description = "Current temperature in a city, in degrees Celsius", readOnly = true)
    public double temperature(@ToolParam(description = "City name") String city) {
        return 12 + Math.floorMod(city.toLowerCase(Locale.ROOT).hashCode(), 15);
    }

    @AgentTool(description = "Current date and time in a time zone", readOnly = true)
    public String time(@ToolParam(description = "IANA time zone id, for example Europe/Paris") String zone) {
        return ZonedDateTime.now(ZoneId.of(zone)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    @AgentTool(description = "Who the server thinks is calling", readOnly = true)
    public String whoami(CallerIdentity caller) {
        return caller.name();
    }
}
