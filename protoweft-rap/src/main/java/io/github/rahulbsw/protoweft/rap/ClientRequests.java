package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The RAP client's requests to tool servers (discovery, invocation, choice answers, lifecycle
 * notifications) and the connector side of callback processing.
 */
final class ClientRequests implements ClientCallbacks.Hooks {

    private static final System.Logger LOG = System.getLogger(RapClientConnector.class.getName());

    private final ClientHttp http;
    private final ClientSettings settings;
    private final RecoveredResults recovered;
    private volatile boolean closed;

    ClientRequests(ClientHttp http, ClientSettings settings, RecoveredResults recovered) {
        this.http = http;
        this.settings = settings;
        this.recovered = recovered;
    }

    @Override
    public void prompt(Runnable task) {
        settings.execute(task);
    }

    @Override
    public RapPromptHandler prompts() {
        return settings.promptHandler();
    }

    /** The answer to a {@code user_choice} (user-choice.md "User Choice Response"). */
    @Override
    public void answerChoice(RapCall call, String responseUrl, int selected) {
        try {
            http.post(URI.create(responseUrl), Map.of("id", call.toolCallId(), "selected", selected),
                    call.serverUrl(), settings.retry());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "could not send the user's choice to " + responseUrl + ": "
                    + e.getMessage());
        }
    }

    @Override
    public void recovered(RapRecoveredResult result) {
        recovered.add(result);
    }

    /** Cancellation goes to every configured server plus the call's own (tool-cancellation.md "Dispatch Behavior"). */
    @Override
    public void cancelRemote(RapCall call) {
        Set<String> servers = new LinkedHashSet<>(settings.serverUrls());
        servers.add(call.serverUrl());
        notifyAll("/cancel_tool_call", Map.of("thread_id", call.threadId(), "tool_call_id", call.toolCallId()),
                List.copyOf(servers));
    }

    void close() {
        closed = true;
    }

    /** {@code GET <server>/.well-known/rap-toolset}, validated (toolsets.md "Discovery Endpoint", "Validation"). */
    Toolset fetchToolset(String server) {
        ClientHttp.Response r = http.get(URI.create(server + "/.well-known/rap-toolset"), server);
        if (r.status() == 401 || r.status() == 403) {
            throw new ProtoweftException(ErrorCategory.AUTHENTICATION, ProtocolId.RAP, String.valueOf(r.status()),
                    "toolset discovery at " + server + " was rejected (HTTP " + r.status() + ")", null);
        }
        if (r.status() / 100 != 2) {
            throw new ProtoweftException(ErrorCategory.PROTOCOL, ProtocolId.RAP, String.valueOf(r.status()),
                    "toolset discovery at " + server + " answered HTTP " + r.status(), null);
        }
        try {
            return Toolset.parse(server, r.body());
        } catch (IllegalArgumentException e) {
            throw new ProtoweftException(ErrorCategory.PROTOCOL, ProtocolId.RAP,
                    "invalid toolset from " + server + ": " + e.getMessage(), e);
        }
    }

    /**
     * Posts an invocation and handles its acknowledgement (tool-invocation.md "Response", "Error
     * Responses"; transport.md "Delivery Requirements"): 200 waits for the callback, 5xx is
     * retried, 401/403 fail with {@code AUTHENTICATION}, any other status is an error result.
     */
    void acknowledge(ClientCallbacks callbacks, ClientCallbacks.PendingCall call, CompletableFuture<ToolOutcome> future,
                     Toolset toolset, Map<String, Object> body) {
        try {
            ClientHttp.Response r = http.post(toolset.endpoint(), body, toolset.serverUrl(), settings.retry());
            if (r.status() == 200 || future.isDone()) {
                return;
            }
            callbacks.close(call);
            if (r.status() == 401 || r.status() == 403) {
                future.completeExceptionally(new ProtoweftException(ErrorCategory.AUTHENTICATION, ProtocolId.RAP,
                        String.valueOf(r.status()), "tool server rejected the invocation's credentials (HTTP "
                        + r.status() + ")", null));
            } else {
                future.complete(ToolOutcome.failure("Error: the tool server did not accept the invocation (HTTP "
                        + r.status() + ")"));
            }
        } catch (RuntimeException e) {
            callbacks.close(call);
            future.completeExceptionally(e);
        }
    }

    /**
     * Best-effort notifications, sent concurrently and once per server (thread-closure.md and
     * tool-cancellation.md "Best-Effort Semantics", "Dispatch Behavior"). Never fails.
     */
    CompletableFuture<Void> notifyAll(String path, Map<String, Object> body, List<String> servers) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Void>> attempts = new ArrayList<>();
        for (String server : servers) {
            CompletableFuture<Void> attempt = new CompletableFuture<>();
            settings.execute(() -> {
                try {
                    http.post(URI.create(server + path), body, server, RapRetryPolicy.NONE);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.INFO, "RAP notification " + path + " to " + server + " failed: "
                            + e.getMessage());
                } finally {
                    attempt.complete(null);
                }
            });
            attempts.add(attempt);
        }
        return CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new));
    }
}
