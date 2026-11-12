package io.github.rahulbsw.protoweft.agui;

import java.util.List;

/**
 * The spec's consumer processing order for one stream: decode (dropping unrecognised events and
 * stripping unrecognised members, with warnings), expand chunks, then verify the sequence. Only
 * events that pass every stage reach application code.
 */
final class ConsumerPipeline {

    private final Decoder decoder;
    private final List<String> warnings;
    private final ChunkExpander expander = new ChunkExpander();
    private final StreamVerifier verifier = new StreamVerifier();

    ConsumerPipeline(List<String> warnings) {
        this.decoder = new Decoder(warnings);
        this.warnings = warnings;
    }

    /**
     * The events one SSE payload stands for, in order (none if it was dropped).
     *
     * @throws AgUiFormatException if the payload is malformed or breaks the sequence rules
     */
    List<AgUiEvent> accept(String data) {
        return accept(Json.parse(data));
    }

    /**
     * The events one Protobuf frame stands for: decoded to the same JSON form, then the same stages.
     * A frame for an event type this version does not know stands for none (warned).
     */
    List<AgUiEvent> acceptFrame(byte[] frame) {
        Object json = ProtoBinding.decode(frame, warnings);
        return json == null ? List.of() : accept(json);
    }

    private List<AgUiEvent> accept(Object json) {
        AgUiEvent event = EventCodec.decode(decoder, json);
        if (event == null) {
            return List.of();
        }
        List<AgUiEvent> expanded = expander.expand(event);
        expanded.forEach(verifier::accept);
        return expanded;
    }

    /** Tool calls the current run started and did not answer. */
    List<String> unansweredToolCalls() {
        return verifier.unansweredToolCalls();
    }
}
