package io.github.rahulbsw.protoweft.rap;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Durable RAP state, so calls and subscriptions survive a restart. The client keeps its pending
 * calls and the keys of callbacks it has processed; the server keeps its active subscriptions
 * (including their callback URLs, which are secrets: whoever holds one can post into the
 * conversation). {@link InMemoryRapStore} is the default; {@link FileRapStore} persists to disk.
 *
 * <p>Implementations must be thread-safe. One store may be shared by several connectors and
 * servers: client calls are scoped by {@link ClientCall#owner()}, server subscriptions by
 * {@link ServerSubscription#server()}.
 */
public interface RapStore {

    /**
     * A tool call the client sent and still expects callbacks for.
     *
     * @param owner        the connector's store key ({@code RapClientConnector.Builder#storeKey})
     * @param token        the secret in the callback URL that identifies the call
     * @param serverUrl    the tool server's base URL
     * @param threadId     the {@code group_id} sent
     * @param toolCallId   the {@code id} sent
     * @param operation    the tool name
     * @param createdAt    when the call was sent
     * @param subscription {@code true} once the tool confirmed a subscription
     */
    record ClientCall(String owner, String token, String serverUrl, String threadId, String toolCallId,
                      String operation, Instant createdAt, boolean subscription) {
        public ClientCall {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(serverUrl, "serverUrl");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public ClientCall asSubscription() {
            return new ClientCall(owner, token, serverUrl, threadId, toolCallId, operation, createdAt, true);
        }
    }

    /**
     * A subscription the server confirmed and keeps delivering events for.
     *
     * @param server      the tool server's store key ({@code RapToolServer.Builder#storeKey})
     * @param threadId    the invocation's {@code group_id}
     * @param toolCallId  the invocation's {@code id}
     * @param operation   the tool name as invoked
     * @param callbackUrl where events go
     * @param caller      the authenticated caller's name
     * @param userId      the invocation's {@code user_id}, or {@code null}
     * @param createdAt   when the subscription started
     */
    record ServerSubscription(String server, String threadId, String toolCallId, String operation,
                              String callbackUrl, String caller, String userId, Instant createdAt) {
        public ServerSubscription {
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(callbackUrl, "callbackUrl");
            Objects.requireNonNull(caller, "caller");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** Inserts or replaces the call with the same token. */
    void saveCall(ClientCall call);

    /** Every stored call. */
    List<ClientCall> calls();

    /** Removes the call; no-op if absent. */
    void deleteCall(String token);

    /**
     * Records that a callback was processed.
     *
     * @return {@code true} if the key is new, {@code false} if it was already recorded
     */
    boolean markProcessed(String key, Instant at);

    /** Whether the key was recorded and not yet purged. */
    boolean isProcessed(String key);

    /** Forgets keys recorded before {@code cutoff}. */
    void purgeProcessed(Instant cutoff);

    /** Inserts or replaces the subscription with the same server, thread and tool call id. */
    void saveSubscription(ServerSubscription subscription);

    /** Every stored subscription. */
    List<ServerSubscription> subscriptions();

    /** Removes the subscription; no-op if absent. */
    void deleteSubscription(String server, String threadId, String toolCallId);
}
