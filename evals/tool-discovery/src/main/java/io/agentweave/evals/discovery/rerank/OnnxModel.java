package io.agentweave.evals.discovery.rerank;

import io.agentweave.discovery.embeddings.WordPieceTokenizer;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * ONNX Runtime session for the reranker experiment: a copy of the embeddings module's internal
 * wrapper, plus output introspection, kept here so the product gains no API for an unshipped
 * feature.
 */
final class OnnxModel implements AutoCloseable {

    static final String INPUT_IDS = "input_ids";
    static final String ATTENTION_MASK = "attention_mask";
    static final String TOKEN_TYPE_IDS = "token_type_ids";

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final boolean takesTokenTypes;

    private OnnxModel(OrtEnvironment environment, OrtSession session) {
        this.environment = environment;
        this.session = session;
        Set<String> inputs = session.getInputNames();
        if (!inputs.contains(INPUT_IDS) || !inputs.contains(ATTENTION_MASK)) {
            throw new IllegalArgumentException("Not a BERT-style model: inputs " + inputs);
        }
        this.takesTokenTypes = inputs.contains(TOKEN_TYPE_IDS);
    }

    /** Creates a session from the bytes of an {@code .onnx} file. */
    static OnnxModel load(byte[] model) throws OrtException {
        return load(model, 0);
    }

    /**
     * Creates a session from the bytes of an {@code .onnx} file.
     *
     * @param intraOpThreads ONNX Runtime's intra-op thread count, or 0 for its default (one per
     *                       physical core)
     */
    static OnnxModel load(byte[] model, int intraOpThreads) throws OrtException {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession session;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            if (intraOpThreads > 0) {
                options.setIntraOpNumThreads(intraOpThreads);
            }
            session = environment.createSession(model, options);
        }
        try {
            return new OnnxModel(environment, session);
        } catch (RuntimeException e) {
            session.close();
            throw e;
        }
    }

    /** The name of the first output (for example {@code last_hidden_state} or {@code logits}). */
    String firstOutputName() {
        try {
            return session.getOutputNames().iterator().next();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Model has no outputs", e);
        }
    }

    /** The declared shape of the first output; symbolic dimensions are -1. */
    long[] firstOutputShape() {
        try {
            if (session.getOutputInfo().get(firstOutputName()).getInfo() instanceof TensorInfo info) {
                return info.getShape();
            }
            throw new IllegalStateException("First model output is not a tensor");
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX Runtime failed: " + e.getMessage(), e);
        }
    }

    /**
     * Runs one sequence and returns the first output's last axis at index 0 of every other axis:
     * the {@code [CLS]} hidden state of an encoder's {@code last_hidden_state [1, L, H]}, or the
     * logits of a classifier's {@code [1, C]}.
     */
    float[] firstVector(WordPieceTokenizer.Encoding encoding) {
        int[] ids = encoding.ids();
        int[] types = encoding.typeIds();
        long[] idValues = new long[ids.length];
        long[] maskValues = new long[ids.length];
        long[] typeValues = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            idValues[i] = ids[i];
            maskValues[i] = 1;
            typeValues[i] = types[i];
        }
        long[] shape = {1, ids.length};
        try (OnnxTensor idTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(idValues), shape);
                OnnxTensor maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(maskValues), shape);
                OnnxTensor typeTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(typeValues), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(INPUT_IDS, idTensor);
            inputs.put(ATTENTION_MASK, maskTensor);
            if (takesTokenTypes) {
                inputs.put(TOKEN_TYPE_IDS, typeTensor);
            }
            try (OrtSession.Result result = session.run(inputs)) {
                return firstVector(result.get(0));
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX Runtime failed: " + e.getMessage(), e);
        }
    }

    private static float[] firstVector(OnnxValue output) {
        if (!(output instanceof OnnxTensor tensor)) {
            throw new IllegalStateException("Model output is not a tensor: " + output.getInfo());
        }
        TensorInfo info = tensor.getInfo();
        long[] shape = info.getShape();
        int width = Math.toIntExact(shape[shape.length - 1]);
        FloatBuffer values = tensor.getFloatBuffer();
        if (values == null) {
            throw new IllegalStateException("Model output is not float: " + info);
        }
        float[] vector = new float[width];
        values.get(vector);
        return vector;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
