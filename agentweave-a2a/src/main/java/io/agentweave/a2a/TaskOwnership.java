package io.agentweave.a2a;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.auth.TaskOperation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task authorization: a task belongs to the {@code CallerIdentity} name that created it, and only
 * that caller may read, continue, cancel, subscribe to, or configure push for it. Anyone the
 * inbound authenticator admits may create tasks. Unrecorded tasks are denied (fail closed).
 */
final class TaskOwnership implements TaskAuthorizationProvider {

    private final Map<String, String> owners = new ConcurrentHashMap<>();

    void claim(String taskId, String caller) {
        owners.putIfAbsent(taskId, caller);
    }

    @Override
    public boolean checkRead(ServerCallContext context, String taskId, TaskOperation operation) {
        if (operation == TaskOperation.LIST_TASKS && taskId.isEmpty()) {
            return true; // the list itself is allowed; each task is then filtered by owner
        }
        return isOwner(context, taskId);
    }

    @Override
    public boolean checkWrite(ServerCallContext context, String taskId, TaskOperation operation) {
        return isOwner(context, taskId);
    }

    @Override
    public boolean checkCreate(ServerCallContext context, TaskOperation operation) {
        return true;
    }

    @Override
    public boolean isTaskRecorded(String taskId) {
        return owners.containsKey(taskId);
    }

    @Override
    public void recordOwnership(ServerCallContext context, String taskId, TaskOperation operation) {
        claim(taskId, HandlerExecutor.caller(context).name());
    }

    private boolean isOwner(ServerCallContext context, String taskId) {
        String owner = owners.get(taskId);
        return owner != null && owner.equals(HandlerExecutor.caller(context).name());
    }
}
