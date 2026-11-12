package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiConformance.Result;
import io.github.rahulbsw.protoweft.agui.AgUiConformance.Status;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunError;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunFinished;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunStarted;

import java.util.ArrayList;
import java.util.List;

/**
 * Judges one run's events for {@link AgUiConformance}, whichever binding carried them: each event's
 * shape, the stream's ordering, its terminal event, the run's identity and declared version. Fed one
 * decoded JSON event at a time, so SSE and Protobuf streams are judged by the same code.
 */
final class ConformanceStream {

    static final String IDENTITY_RULE = "every run boundary carries the input's threadId; the last run is the "
            + "requested one (its runId) and earlier, replayed runs use other runIds";
    static final String VERSION_RULE = "RUN_STARTED declares the producer's protocolVersion as MAJOR.MINOR";

    private final List<String> shape = new ArrayList<>();
    private final List<String> sequence = new ArrayList<>();
    private final List<AgUiEvent> events = new ArrayList<>();
    private final ChunkExpander expander = new ChunkExpander();
    private final StreamVerifier verifier = new StreamVerifier();

    /** One frame's event in JSON form; {@code warnings} are any its binding already raised for it. */
    void accept(String frame, Object json, List<String> warnings) {
        List<String> found = new ArrayList<>(warnings);
        AgUiEvent event;
        try {
            event = json == null ? null : EventCodec.decode(new Decoder(found), json);
        } catch (AgUiFormatException e) {
            shape.add(frame + ": " + e.getMessage());
            return;
        } finally {
            found.forEach(w -> shape.add(frame + ": " + w));
        }
        if (event == null) {
            return;
        }
        events.add(event);
        try {
            for (AgUiEvent e : expander.expand(event)) {
                verifier.accept(e);
            }
        } catch (AgUiFormatException e) {
            sequence.add(frame + " (" + event.type() + "): " + e.getMessage());
        }
    }

    /**
     * The event and lifecycle checks, ids prefixed with {@code prefix} (empty for SSE): shapes,
     * sequence, terminal event, identity, declared version.
     */
    List<Result> results(String prefix, String threadId, String runId) {
        List<Result> results = new ArrayList<>();
        results.add(check(prefix + "events/described-shapes",
                "every event matches the schema: known types, no undescribed members, absent fields omitted not null",
                shape.isEmpty(), String.join("\n", shape)));
        results.add(check(prefix + "lifecycle/sequence",
                "the stream follows the ordering rules (starts with RUN_STARTED or RUN_ERROR, items opened before "
                        + "continued, closed before RUN_FINISHED, attribution agrees)", sequence.isEmpty(),
                String.join("\n", sequence)));
        AgUiEvent last = events.isEmpty() ? null : events.get(events.size() - 1);
        results.add(check(prefix + "lifecycle/terminal",
                "the stream ends with the run's terminal event (RUN_FINISHED or RUN_ERROR)",
                last instanceof RunFinished || last instanceof RunError,
                last == null ? "no events" : "last event is " + last.type()));
        List<AgUiEvent> boundaries = events.stream()
                .filter(e -> e instanceof RunStarted || e instanceof RunFinished).toList();
        RunStarted requested = events.stream().filter(RunStarted.class::isInstance).map(RunStarted.class::cast)
                .reduce((a, b) -> b).orElse(null);
        if (requested == null && last instanceof RunError error) {
            // A valid stream: the run failed before it started (RUN_ERROR carries no identity or version).
            String why = "the agent reported RUN_ERROR before starting the run: " + error.message();
            results.add(new Result(prefix + "lifecycle/identity", IDENTITY_RULE, Status.SKIP, why));
            results.add(new Result(prefix + "version/declared", VERSION_RULE, Status.SKIP, why));
            return results;
        }
        results.add(identity(prefix, boundaries, threadId, runId));
        String declared = requested == null ? null : requested.protocolVersion();
        results.add(check(prefix + "version/declared", VERSION_RULE, declared != null && declared.matches("\\d+\\.\\d+"),
                declared == null ? "no protocolVersion on RUN_STARTED" : "declared '" + declared + "'"));
        return results;
    }

    /**
     * Spec "Run Input", identity: every run on the stream carries the input's {@code threadId} on its
     * boundary events; the requested run (the last) echoes the input's {@code runId}; a replayed run
     * carries its own, and a {@code runId} is never reused on a thread.
     */
    private static Result identity(String prefix, List<AgUiEvent> boundaries, String threadId, String runId) {
        List<String> problems = new ArrayList<>();
        for (AgUiEvent e : boundaries) {
            String thread = e instanceof RunStarted s ? s.threadId() : ((RunFinished) e).threadId();
            if (!threadId.equals(thread)) {
                problems.add(e.type() + " for thread '" + thread + "' (expected '" + threadId + "')");
            }
        }
        List<RunStarted> starts = boundaries.stream().filter(RunStarted.class::isInstance).map(RunStarted.class::cast)
                .toList();
        if (starts.isEmpty()) {
            problems.add("no RUN_STARTED");
        } else {
            RunStarted lastStart = starts.get(starts.size() - 1);
            if (!runId.equals(lastStart.runId())) {
                problems.add("the last run is '" + lastStart.runId() + "', not the requested '" + runId + "'");
            }
            starts.subList(0, starts.size() - 1).stream().filter(s -> runId.equals(s.runId())).findFirst()
                    .ifPresent(s -> problems.add("an earlier run reuses the requested runId '" + runId + "'"));
        }
        if (!boundaries.isEmpty() && boundaries.get(boundaries.size() - 1) instanceof RunFinished f
                && !runId.equals(f.runId())) {
            problems.add("the final RUN_FINISHED is for run '" + f.runId() + "'");
        }
        return check(prefix + "lifecycle/identity", IDENTITY_RULE, problems.isEmpty(), String.join("\n", problems));
    }

    static Result check(String id, String rule, boolean ok, String detail) {
        return new Result(id, rule, ok ? Status.PASS : Status.FAIL, ok ? null : detail);
    }
}
