package io.github.rahulbsw.protoweft.a2a;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.server.FixedInstance;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.BasePushNotificationSender;
import org.a2aproject.sdk.server.tasks.DefaultPushNotificationUrlValidator;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.PushNotificationUrlValidator;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Wires a2a-java's framework-neutral server pieces (task store, queues, event bus, push,
 * {@code DefaultRequestHandler}, {@code JSONRPCHandler}) without a CDI container.
 */
final class SdkServer {

    /** Name a2a-java 1.5.0.Final gives the event-bus thread it starts. */
    private static final String BUS_THREAD_NAME = "MainEventBusProcessor";

    private final A2aAgentServer.Builder config;
    private final ExecutorService agentExecutor;
    private final boolean ownsAgentExecutor;
    private final ExecutorService events;

    private final Thread busThread;
    private final DefaultRequestHandler requestHandler;

    private volatile JSONRPCHandler jsonRpc;
    private volatile AgentCard publicCard;
    private volatile String publicCardJson;

    SdkServer(A2aAgentServer.Builder config, HandlerExecutor executor, TaskOwnership ownership) {
        this.config = config;
        this.ownsAgentExecutor = config.agentExecutor == null;
        this.agentExecutor = ownsAgentExecutor
                ? Executors.newCachedThreadPool(SdkInfo.daemonThreads("protoweft-a2a-agent-"))
                : config.agentExecutor;
        // Event consumers block while polling queues: keep them unbounded and apart from agent work.
        this.events = Executors.newCachedThreadPool(SdkInfo.daemonThreads("protoweft-a2a-events-"));

        InMemoryTaskStore taskStore = new InMemoryTaskStore(ownership);
        MainEventBus bus = new MainEventBus();
        InMemoryQueueManager queues = new InMemoryQueueManager(taskStore, bus);
        InMemoryPushNotificationConfigStore pushStore = config.push ? new InMemoryPushNotificationConfigStore() : null;
        PushNotificationUrlValidator urls = config.insecurePushTargets
                ? SdkServer::allowHttpTargets : new DefaultPushNotificationUrlValidator();
        PushNotificationSender pushSender = pushStore == null ? PushNotificationSender.NOOP
                : new BasePushNotificationSender(pushStore, urls);
        MainEventBusProcessor processor = new MainEventBusProcessor(bus, taskStore, pushSender, queues);
        processor.setPushNotificationExecutor(events);
        // DefaultRequestHandler starts the processor's daemon thread itself; its stop() is
        // package-private, so remember which thread it started and interrupt it on close.
        synchronized (SdkServer.class) {
            Set<Thread> before = busThreads();
            this.requestHandler = DefaultRequestHandler.builder()
                    .agentExecutor(executor)
                    .taskStore(taskStore)
                    .queueManager(queues)
                    .pushConfigStore(pushStore)
                    .pushNotificationsEnabled(config.push)
                    .mainEventBusProcessor(processor)
                    .executor(agentExecutor)
                    .eventConsumerExecutor(events)
                    .authorizationProvider(ownership)
                    .agentCompletionTimeoutSeconds(Math.toIntExact(config.agentTimeout.toSeconds()))
                    .build();
            Set<Thread> started = busThreads();
            started.removeAll(before);
            this.busThread = started.size() == 1 ? started.iterator().next() : null;
        }
    }

    private static Set<Thread> busThreads() {
        Set<Thread> threads = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (BUS_THREAD_NAME.equals(t.getName())) {
                threads.add(t);
            }
        }
        return threads;
    }

    /** The SDK's JSON-RPC handler, created on first use so the public URL may be known late. */
    JSONRPCHandler jsonRpc() {
        JSONRPCHandler h = jsonRpc;
        if (h == null) {
            synchronized (this) {
                if (jsonRpc == null) {
                    AgentCard card = card(config.descriptor);
                    AgentCard extended = config.extended == null ? null : card(config.extended);
                    jsonRpc = new JSONRPCHandler(new FixedInstance<>(card),
                            extended == null ? FixedInstance.empty() : new FixedInstance<>(extended),
                            requestHandler, events, null);
                    publicCard = card;
                }
                h = jsonRpc;
            }
        }
        return h;
    }

    AgentCard publicCard() {
        jsonRpc();
        return publicCard;
    }

    /** The public card as served at the well-known path; validated against the registered transports. */
    String publicCardJson() {
        String json = publicCardJson;
        if (json == null) {
            AgentCard card = jsonRpc().getAgentCard();
            try {
                json = JsonUtil.toJson(card);
            } catch (Exception e) {
                throw new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.A2A, "cannot serialize agent card", e);
            }
            publicCardJson = json;
        }
        return json;
    }

    ExecutorService events() {
        return events;
    }

    void close() {
        if (busThread != null) {
            busThread.interrupt();
        }
        if (ownsAgentExecutor) {
            agentExecutor.shutdownNow();
        }
        events.shutdownNow();
    }

    private AgentCard card(AgentDescriptor descriptor) {
        String url = interfaceUrl(config.publicUrl);
        return AgentCard.builder()
                .name(descriptor.name())
                .description(descriptor.description())
                .version(descriptor.version())
                .capabilities(AgentCapabilities.builder()
                        .streaming(config.streaming)
                        .pushNotifications(config.push)
                        .extendedAgentCard(config.extended != null)
                        .build())
                .defaultInputModes(config.inputModes)
                .defaultOutputModes(config.outputModes)
                .skills(A2aMapping.toSkills(descriptor))
                .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), url)))
                .build();
    }

    private String interfaceUrl(Supplier<String> publicUrl) {
        String base = publicUrl.get();
        if (base == null || base.isBlank()) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.A2A, "public URL is not set");
        }
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return base + config.path;
    }

    /** Development/test policy: http or https, any host including loopback and private networks. */
    private static void allowHttpTargets(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null) {
            throw new IllegalArgumentException("push notification URL must be an absolute http(s) URL: " + url);
        }
    }
}
