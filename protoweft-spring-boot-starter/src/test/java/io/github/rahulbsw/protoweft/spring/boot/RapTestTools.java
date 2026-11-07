package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import io.github.rahulbsw.protoweft.rap.RapCallContext;
import io.github.rahulbsw.protoweft.rap.RapSubscription;

import java.util.List;

/** {@code @AgentTool} methods used by the RAP tests; they use {@link RapCallContext} when served over RAP. */
public class RapTestTools {

    @AgentTool(description = "Echoes the text")
    public String echo(@ToolParam(description = "text") String text) {
        return text;
    }

    @AgentTool(description = "Names the authenticated caller")
    public String whoami(CallerIdentity caller) {
        return caller.name();
    }

    @AgentTool(description = "Streams temperature readings for a city")
    public String watch(@ToolParam(description = "city") String city, RapCallContext rap) {
        if (!rap.available()) {
            return "subscriptions need RAP";
        }
        RapSubscription subscription = rap.subscribe();
        // Events emitted before the result is delivered are held and sent right after it, in order.
        subscription.emit(city + " 21.5");
        subscription.emit(city + " 22.0", true);
        subscription.close(city + " done", false);
        return "watching " + city;
    }

    @AgentTool(description = "Asks the user for the unit")
    public String unit(RapCallContext rap) {
        return List.of("celsius", "fahrenheit").get(rap.askUser("Which unit?", List.of("celsius", "fahrenheit"), 0));
    }
}
