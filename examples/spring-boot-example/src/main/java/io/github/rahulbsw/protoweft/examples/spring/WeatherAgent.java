package io.github.rahulbsw.protoweft.examples.spring;

import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The A2A agent: answers "weather in <city>" from {@link WeatherTools}, and asks for the city
 * (A2A {@code input-required}) when the message names none.
 */
@Component
public class WeatherAgent implements AgentHandler {

    private static final Pattern CITY = Pattern.compile("\\bin\\s+([\\p{L} .'-]+?)\\s*\\??$", Pattern.CASE_INSENSITIVE);

    private final WeatherTools tools;

    public WeatherAgent(WeatherTools tools) {
        this.tools = tools;
    }

    @Override
    public void handle(AgentRequest request, AgentTaskContext task) {
        String text = request.text().trim();
        Matcher m = CITY.matcher(text);
        String city = m.find() ? m.group(1) : request.resumed() ? text : null;
        if (city == null || city.isBlank()) {
            task.requireInput("Which city?");
            return;
        }
        String answer = "It is " + tools.temperature(city) + " °C in " + city + ".";
        if (request.resumed()) {
            task.complete(answer); // an existing task is answered by completing it
        } else {
            task.reply(answer); // a one-shot question gets a direct message
        }
    }
}
