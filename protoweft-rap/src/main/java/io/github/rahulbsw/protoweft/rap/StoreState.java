package io.github.rahulbsw.protoweft.rap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The contents of a {@link RapStore}, with a plain-value form for {@link FileRapStore}. Not thread-safe. */
final class StoreState {

    final Map<String, RapStore.ClientCall> calls = new LinkedHashMap<>();
    final Map<String, Instant> processed = new LinkedHashMap<>();
    final Map<String, RapStore.ServerSubscription> subscriptions = new LinkedHashMap<>();

    static String subscriptionKey(String server, String threadId, String toolCallId) {
        return server + "\u0000" + threadId + "\u0000" + toolCallId;
    }

    void saveSubscription(RapStore.ServerSubscription s) {
        subscriptions.put(subscriptionKey(s.server(), s.threadId(), s.toolCallId()), s);
    }

    boolean markProcessed(String key, Instant at) {
        if (processed.containsKey(key)) {
            return false;
        }
        processed.put(key, at);
        return true;
    }

    void purgeProcessed(Instant cutoff) {
        processed.values().removeIf(at -> at.isBefore(cutoff));
    }

    Map<String, Object> toPlain() {
        List<Object> callList = new ArrayList<>();
        for (RapStore.ClientCall c : calls.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("owner", c.owner());
            m.put("token", c.token());
            m.put("serverUrl", c.serverUrl());
            m.put("threadId", c.threadId());
            m.put("toolCallId", c.toolCallId());
            m.put("operation", c.operation());
            m.put("createdAt", c.createdAt().toString());
            m.put("subscription", c.subscription());
            callList.add(m);
        }
        Map<String, Object> processedMap = new LinkedHashMap<>();
        processed.forEach((k, v) -> processedMap.put(k, v.toString()));
        List<Object> subList = new ArrayList<>();
        for (RapStore.ServerSubscription s : subscriptions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("server", s.server());
            m.put("threadId", s.threadId());
            m.put("toolCallId", s.toolCallId());
            m.put("operation", s.operation());
            m.put("callbackUrl", s.callbackUrl());
            m.put("caller", s.caller());
            m.put("userId", s.userId());
            m.put("createdAt", s.createdAt().toString());
            subList.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 1);
        out.put("calls", callList);
        out.put("processed", processedMap);
        out.put("subscriptions", subList);
        return out;
    }

    static StoreState fromPlain(Map<String, Object> plain) {
        StoreState state = new StoreState();
        List<Object> callList = Json.array(plain.get("calls"));
        if (callList != null) {
            for (Object o : callList) {
                Map<String, Object> m = Json.object(o);
                RapStore.ClientCall c = new RapStore.ClientCall(str(m, "owner"), str(m, "token"), str(m, "serverUrl"),
                        str(m, "threadId"), str(m, "toolCallId"), str(m, "operation"),
                        Instant.parse(str(m, "createdAt")), Boolean.TRUE.equals(m.get("subscription")));
                state.calls.put(c.token(), c);
            }
        }
        Map<String, Object> processedMap = Json.object(plain.get("processed"));
        if (processedMap != null) {
            processedMap.forEach((k, v) -> state.processed.put(k, Instant.parse(String.valueOf(v))));
        }
        List<Object> subList = Json.array(plain.get("subscriptions"));
        if (subList != null) {
            for (Object o : subList) {
                Map<String, Object> m = Json.object(o);
                state.saveSubscription(new RapStore.ServerSubscription(str(m, "server"), str(m, "threadId"),
                        str(m, "toolCallId"), str(m, "operation"), str(m, "callbackUrl"), str(m, "caller"),
                        m.get("userId") instanceof String u ? u : null, Instant.parse(str(m, "createdAt"))));
            }
        }
        return state;
    }

    private static String str(Map<String, Object> m, String key) {
        if (m == null || !(m.get(key) instanceof String s)) {
            throw new IllegalStateException("corrupt RAP store: missing '" + key + "'");
        }
        return s;
    }
}
