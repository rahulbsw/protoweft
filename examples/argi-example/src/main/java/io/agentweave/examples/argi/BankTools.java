package io.agentweave.examples.argi;

import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.tool.AgentTool;
import io.agentweave.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plain bean methods the AgentWeave starter registers as local tools (source {@code local}), so
 * the model sees them as {@code local__balance}, {@code local__rate} and {@code local__transfer}.
 * {@code transfer} is the sensitive one: the agent asks for approval before calling it.
 */
@Component
public class BankTools {

    /** Transfers made, for the demo and its test. */
    final List<String> transfers = new CopyOnWriteArrayList<>();

    @AgentTool(description = "Current balance of a bank account, in euros", readOnly = true)
    public double balance(@ToolParam(description = "Account name, for example main or savings") String account) {
        return 100 + Math.floorMod(account.toLowerCase(Locale.ROOT).hashCode(), 900);
    }

    @AgentTool(description = "Exchange rate between two currencies", readOnly = true)
    public double rate(@ToolParam(description = "ISO code of the currency to convert from") String from,
                       @ToolParam(description = "ISO code of the currency to convert to") String to) {
        return from.equalsIgnoreCase(to) ? 1.0 : 1.08;
    }

    @AgentTool(description = "Transfers money to a payee")
    public String transfer(@ToolParam(description = "Payee name") String to,
                           @ToolParam(description = "Amount in euros") int amount,
                           CallerIdentity caller) {
        String done = "Transferred " + amount + " EUR to " + to + " for " + caller.name();
        transfers.add(done);
        return done;
    }
}
