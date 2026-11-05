package io.agentweave.evals.discovery.data;

import io.agentweave.core.tool.ToolSpec;

import java.util.Objects;

/**
 * One tool of an evaluation dataset.
 *
 * @param id          globally unique, stable id, for example {@code mcptoolbench:paypal/create_product}
 *                    or {@code toolret:toolbench_tool_2857}; gold labels refer to this id
 * @param dataset     the dataset the tool comes from, for example {@code mcptoolbench} or
 *                    {@code toolret/web}
 * @param spec        the tool as a model would see it; {@code spec.name()} is the dataset's own
 *                    name, which may not be provider-safe (see {@link EvalCatalog})
 * @param rawDocument the dataset's original tool document (ToolRet {@code documentation}), or
 *                    {@code null}; retrievers that want ToolRet's exact indexing text can use it
 */
public record EvalTool(String id, String dataset, ToolSpec spec, String rawDocument) {

    public EvalTool {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(spec, "spec");
    }

    public EvalTool(String id, String dataset, ToolSpec spec) {
        this(id, dataset, spec, null);
    }
}
