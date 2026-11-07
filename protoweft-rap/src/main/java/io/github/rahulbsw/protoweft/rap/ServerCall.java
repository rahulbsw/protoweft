package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.ToolContexts;
import io.github.rahulbsw.protoweft.core.tool.ToolEvent;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.core.tool.ToolSubscription;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One accepted invocation running on the server: the tool call, its context and its callbacks. */
final class ServerCall implements RapCallContext {

    private static final System.Logger LOG = System.getLogger(RapToolServer.class.getName());

    /**
     * A validated invocation (tool-invocation.md "Fields").
     *
     * @param toolName the source's tool name, or {@code null} for an unknown operation
     */
    record Invocation(String operation, String toolName, Map<String, Object> arguments, String id, String callId,
                      URI callbackUrl, String groupId, List<String> ancestors, String userId) {
    }

    private final ServerState state;
    private final Invocation inv;
    private final CallerIdentity caller;
    private final String baseUrl;
    private final CallbackSender.Queue queue;
    private final List<Runnable> cancelHooks = new ArrayList<>();
    private boolean cancelled;
    private SubscriptionHandle subscription;

    ServerCall(ServerState state, Invocation inv, CallerIdentity caller, String baseUrl) {
        this.state = state;
        this.inv = inv;
        this.caller = caller;
        this.baseUrl = baseUrl;
        this.queue = state.sender.queue(inv.callbackUrl());
    }

    String key() {
        return ServerState.key(inv.groupId(), inv.id());
    }

    /** Runs the tool and delivers its result, then any events held meanwhile (lifecycle.md "Processing an Invocation"). */
    void run() {
        Map<String, Object> result;
        try {
            if (inv.toolName() == null) {
                result = ServerResults.errorResult(inv.groupId(), inv.id(), inv.callId(),
                        "unknown operation: " + inv.operation());
            } else {
                ToolOutcome outcome = state.tools.invoke(ToolInvocation.of(inv.toolName(), inv.arguments()),
                        toolCaller(), ToolProgress.NOOP, ToolContexts.of(RapCallContext.class, this));
                if (outcome == null) {
                    outcome = new ToolOutcome(List.of(), false, null);
                }
                outcome.subscription().ifPresent(this::bridge);
                result = ServerResults.toolResult(inv.groupId(), inv.id(), inv.callId(), outcome, current() != null);
            }
        } catch (ProtoweftException e) {
            result = ServerResults.errorResult(inv.groupId(), inv.id(), inv.callId(),
                    e.getMessage() != null ? e.getMessage() : e.category().name());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "RAP tool '" + inv.operation() + "' failed", e);
            result = ServerResults.errorResult(inv.groupId(), inv.id(), inv.callId(),
                    "the tool failed (" + e.getClass().getSimpleName() + ")");
        } finally {
            state.running.remove(key(), this);
        }
        queue.send(result);
        SubscriptionHandle sub = current();
        if (sub != null) {
            sub.release();
        }
    }

    private CallerIdentity toolCaller() {
        if (inv.userId() == null) {
            return caller;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(caller.attributes());
        attributes.put(RapToolServer.USER_ID_ATTRIBUTE, inv.userId());
        return new CallerIdentity(caller.name(), attributes);
    }

    /** A source's own {@link ToolSubscription} (for example a re-served RAP connector) becomes this call's subscription. */
    private void bridge(ToolSubscription source) {
        if (current() != null) {
            return;
        }
        RapSubscription target = subscribe();
        target.onCancel(source::cancel);
        source.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ToolEvent event) {
                boolean associative = Boolean.TRUE.equals(event.metadata().get("associative"));
                if (event.last()) {
                    target.close(ServerResults.asText(event.content()), associative);
                } else {
                    target.emit(event.content(), associative);
                }
            }

            @Override
            public void onError(Throwable error) {
                target.close(ServerResults.error(String.valueOf(error.getMessage())), false);
            }

            @Override
            public void onComplete() {
                target.close();
            }
        });
    }

    private synchronized SubscriptionHandle current() {
        return subscription;
    }

    /** {@code /cancel_tool_call} for this running call (tool-cancellation.md "Cancellation Behavior"). */
    void cancel() {
        List<Runnable> hooks;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            hooks = new ArrayList<>(cancelHooks);
            cancelHooks.clear();
        }
        CompletableFuture<Integer> choice = state.choices.get(inv.id());
        if (choice != null) {
            choice.completeExceptionally(new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.RAP,
                    "the tool call was cancelled"));
        }
        hooks.forEach(ServerCall::runQuietly);
    }

    @Override
    public String operation() {
        return inv.operation();
    }

    @Override
    public String threadId() {
        return inv.groupId();
    }

    @Override
    public String toolCallId() {
        return inv.id();
    }

    @Override
    public Optional<String> userId() {
        return Optional.ofNullable(inv.userId());
    }

    @Override
    public List<String> threadAncestors() {
        return inv.ancestors();
    }

    @Override
    public synchronized RapSubscription subscribe() {
        if (subscription == null) {
            subscription = state.openSubscription(new RapStore.ServerSubscription(state.storeKey, inv.groupId(),
                    inv.id(), inv.operation(), inv.callbackUrl().toString(), caller.name(), inv.userId(),
                    state.clock.instant()), queue, false);
        }
        return subscription;
    }

    /** The {@code oauth} message (oauth.md "OAuth Message"; its URL MUST use HTTPS, "Security Considerations"). */
    @Override
    public void requestAuthorization(String authUrl) {
        Objects.requireNonNull(authUrl, "authUrl");
        if (!authUrl.regionMatches(true, 0, "https://", 0, 8)) {
            throw new IllegalArgumentException("the authorization URL must use https");
        }
        Map<String, Object> msg = message("oauth");
        msg.put("auth_url", authUrl);
        queue.send(msg);
    }

    @Override
    public int askUser(String prompt, List<String> choices, int defaultIndex) {
        return askUser(prompt, choices, defaultIndex, state.choiceTimeout);
    }

    /** The {@code user_choice} message and its answer (user-choice.md). */
    @Override
    public int askUser(String prompt, List<String> choices, int defaultIndex, Duration timeout) {
        Objects.requireNonNull(prompt, "prompt");
        List<String> options = List.copyOf(choices);
        if (options.isEmpty() || defaultIndex < 0 || defaultIndex >= options.size()) {
            throw new IllegalArgumentException("choices must not be empty and defaultIndex must be a valid index");
        }
        CompletableFuture<Integer> answer = new CompletableFuture<>();
        synchronized (this) {
            if (cancelled) {
                throw new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.RAP, "the tool call was cancelled");
            }
            if (state.choices.putIfAbsent(inv.id(), answer) != null) {
                throw new IllegalStateException("a choice is already pending for tool call " + inv.id());
            }
        }
        try {
            Map<String, Object> msg = message("user_choice");
            msg.put("prompt", prompt);
            msg.put("choices", options);
            msg.put("default", defaultIndex);
            msg.put("response_url", baseUrl + "/user_choice_response");
            queue.send(msg).thenAccept(delivered -> {
                if (!delivered) {
                    answer.completeExceptionally(new ProtoweftException(ErrorCategory.CONNECTION, ProtocolId.RAP,
                            "user choice flow failed: the prompt could not be delivered"));
                }
            });
            int selected = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (selected >= options.size()) {
                throw new ProtoweftException(ErrorCategory.PROTOCOL, ProtocolId.RAP,
                        "user choice flow failed: invalid selection " + selected);
            }
            return selected;
        } catch (TimeoutException e) {
            return defaultIndex;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof ProtoweftException pe ? pe
                    : new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.RAP, "user choice flow failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.RAP, "interrupted waiting for a choice", e);
        } finally {
            state.choices.remove(inv.id(), answer);
        }
    }

    @Override
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void onCancel(Runnable action) {
        Objects.requireNonNull(action, "action");
        synchronized (this) {
            if (!cancelled) {
                cancelHooks.add(action);
                return;
            }
        }
        runQuietly(action);
    }

    private Map<String, Object> message(String type) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("group_id", inv.groupId());
        msg.put("id", inv.id());
        if (inv.callId() != null) {
            msg.put("call_id", inv.callId());
        }
        return msg;
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "RAP cancel hook failed", e);
        }
    }
}
