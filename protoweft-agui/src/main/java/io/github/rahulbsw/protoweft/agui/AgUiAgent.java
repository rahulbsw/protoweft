package io.github.rahulbsw.protoweft.agui;

/**
 * An agent served over AG-UI. One call per run, on a server worker thread:
 *
 * <pre>{@code
 * AgUiAgent agent = (input, run) -> {
 *     AgUiEmitter.TextMessage reply = run.startMessage();
 *     for (String token : model.stream(input.messages())) {
 *         reply.delta(token);
 *     }
 *     reply.end();
 *     run.finish();
 * };
 * }</pre>
 *
 * <p>{@code RUN_STARTED} has already been sent when this is called. If the method returns without
 * ending the run, the server closes whatever is still open and finishes it; if it throws, the run
 * ends with {@code RUN_ERROR} carrying the exception message. The server is stateless: everything
 * the run knows arrives in {@link RunAgentInput}.
 */
@FunctionalInterface
public interface AgUiAgent {

    void run(RunAgentInput input, AgUiRun run) throws Exception;
}
