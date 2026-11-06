package io.github.rahulbsw.protoweft.a2a;

import com.google.protobuf.MessageOrBuilder;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;

/**
 * Domain result → protobuf message for JSON-RPC serialization. Replicates the private
 * {@code convertToProto} of a2a-java's reference {@code A2AServerRoutes} (1.5.0.Final).
 */
final class ProtoResults {

    private ProtoResults() {
    }

    static MessageOrBuilder toProto(A2AResponse<?> response) {
        if (response instanceof GetTaskResponse r) {
            return ProtoUtils.ToProto.task(r.getResult());
        } else if (response instanceof CancelTaskResponse r) {
            return ProtoUtils.ToProto.task(r.getResult());
        } else if (response instanceof SendMessageResponse r) {
            return ProtoUtils.ToProto.taskOrMessage(r.getResult());
        } else if (response instanceof ListTasksResponse r) {
            return ProtoUtils.ToProto.listTasksResult(r.getResult());
        } else if (response instanceof CreateTaskPushNotificationConfigResponse r) {
            return ProtoUtils.ToProto.createTaskPushNotificationConfigResponse(r.getResult());
        } else if (response instanceof GetTaskPushNotificationConfigResponse r) {
            return ProtoUtils.ToProto.getTaskPushNotificationConfigResponse(r.getResult());
        } else if (response instanceof ListTaskPushNotificationConfigsResponse r) {
            return ProtoUtils.ToProto.listTaskPushNotificationConfigsResponse(r.getResult());
        } else if (response instanceof DeleteTaskPushNotificationConfigResponse) {
            return com.google.protobuf.Empty.getDefaultInstance();
        } else if (response instanceof GetExtendedAgentCardResponse r) {
            return ProtoUtils.ToProto.getExtendedCardResponse(r.getResult());
        }
        throw new IllegalArgumentException("Unknown response type: " + response.getClass().getName());
    }
}
