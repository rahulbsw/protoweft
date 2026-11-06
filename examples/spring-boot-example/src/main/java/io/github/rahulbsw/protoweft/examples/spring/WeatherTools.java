package io.github.rahulbsw.protoweft.examples.spring;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/** Plain bean methods, defined once and served as tools over every enabled protocol. */
@Component
public class WeatherTools {

    /** A daily forecast entry; records become JSON objects. */
    public record Day(int day, double celsius, String sky) {
    }

    @AgentTool(description = "Current temperature in a city, in degrees Celsius")
    public double temperature(@ToolParam(description = "City name") String city) {
        return 12 + Math.floorMod(city.toLowerCase(Locale.ROOT).hashCode(), 15);
    }

    @AgentTool(description = "Daily forecast for a city")
    public List<Day> forecast(@ToolParam(description = "City name") String city,
                              @ToolParam(description = "Number of days, 1 to 7") int days) {
        if (days < 1 || days > 7) {
            throw new IllegalArgumentException("days must be between 1 and 7");
        }
        double today = temperature(city);
        return IntStream.range(0, days)
                .mapToObj(d -> new Day(d, today + (d % 3) - 1, d % 2 == 0 ? "sunny" : "cloudy"))
                .toList();
    }

    @AgentTool(description = "Who the server thinks is calling")
    public String whoami(CallerIdentity caller) {
        return caller.name();
    }
}
