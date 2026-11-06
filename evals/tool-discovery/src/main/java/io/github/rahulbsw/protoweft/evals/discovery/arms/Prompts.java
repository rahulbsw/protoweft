package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;

/**
 * Prompts shared by all arms, so arms differ only in how tools are exposed. Arms that add a
 * search tool append their own instructions to {@link #SYSTEM}.
 */
public final class Prompts {

    /** The base system prompt. */
    public static final String SYSTEM = "You complete the user's request by calling the single most appropriate "
            + "tool from the tools you have. Call it directly, filling its arguments from the request; do not ask "
            + "follow-up questions. If no tool fits, answer briefly without calling a tool.";

    private Prompts() {
    }

    /** The user message for a task: the query only (ToolRet instructions are for retrievers). */
    public static String user(EvalTask task) {
        return task.query();
    }
}
