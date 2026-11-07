package io.github.rahulbsw.protoweft.core.tool;

/**
 * A per-call context that one server protocol hands to the tools it runs, beyond the arguments,
 * the caller and {@link ToolProgress}: for example RAP's {@code RapCallContext}, which lets a tool
 * start a subscription or ask the user to choose. Protocol modules define a context as an
 * interface extending this one, and pass instances in {@link ToolContexts} through
 * {@link ToolSource#invoke(ToolInvocation, io.github.rahulbsw.protoweft.core.server.CallerIdentity,
 * ToolProgress, ToolContexts)}; the core never knows the concrete types.
 *
 * <p>An {@link AgentTool} method receives a context by declaring a parameter of its interface type,
 * which is not part of the tool's schema; a programmatic tool uses a {@link ContextualToolHandler}
 * and {@link ToolContexts#resolve(Class)}. When the protocol serving the call does not provide the
 * context, the tool gets a stand-in whose {@link #available()} is {@code false} and whose other
 * methods throw {@link io.github.rahulbsw.protoweft.core.ProtoweftException} with
 * {@link io.github.rahulbsw.protoweft.core.ErrorCategory#UNSUPPORTED}.
 */
public interface ToolContext {

    /** {@code false} for the stand-in given when the serving protocol doesn't provide this context. */
    default boolean available() {
        return true;
    }
}
