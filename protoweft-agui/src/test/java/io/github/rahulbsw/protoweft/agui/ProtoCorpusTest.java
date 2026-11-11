package io.github.rahulbsw.protoweft.agui;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec's binary conformance surface: "a corpus of canonical events pins the encoded bytes, and
 * every first-party encoder MUST reproduce the corpus byte for byte". The corpus is the official
 * one (ag-ui-protocol/ag-ui @ 07802ae: {@code spec/1.0/fixtures/*Event/valid} and the committed
 * bytes of {@code @ag-ui/proto} 1.0.4), vendored and pinned here, as are the wire schema files.
 */
class ProtoCorpusTest {

    private static final String CORPUS = "proto-corpus/";
    /** SHA-256 of {@code proto-corpus/SHA256SUMS}, which pins every fixture. */
    private static final String CORPUS_SHA256 = "9b1e435e98b02c4d6f3521ee390d90ef28916e0e6edcb1dbb24e0f96de246ddc";
    private static final Map<String, String> PROTO_SHA256 = Map.of(
            "events.proto", "b311214f85c8c399d7ac90ba9e3cf64f78bc1c1557ab6c47f2536aa48540b9ab",
            "types.proto", "aa3d5017168336e152300d4328162b5cd41dbab9efba92593c13b79b4d50bc6a",
            "patch.proto", "a1aa17b357fe712b0a70569b03368cce00d8c8a1783a8aa97f7239aa9cb05fd1");

    @Test
    void theVendoredWireSchemaIsTheOfficialOne() throws IOException {
        for (Map.Entry<String, String> e : PROTO_SHA256.entrySet()) {
            assertThat(sha256(Files.readAllBytes(Path.of("src/main/agui-proto", e.getKey()))))
                    .as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void theCorpusIsTheOfficialOneAndCoversEveryEventType() throws IOException {
        byte[] sums = resource("SHA256SUMS");
        assertThat(sha256(sums)).isEqualTo(CORPUS_SHA256);
        Set<String> types = new TreeSet<>();
        for (String line : new String(sums, StandardCharsets.UTF_8).split("\n")) {
            String[] parts = line.split("  ", 2);
            assertThat(sha256(resource(parts[1]))).as(parts[1]).isEqualTo(parts[0]);
            if (parts[1].startsWith("json/")) {
                types.add((String) ((Map<?, ?>) Json.parse(resource(parts[1]))).get("type"));
            }
        }
        assertThat(types).hasSize(31);
    }

    @TestFactory
    Stream<DynamicTest> everyCanonicalEventEncodesToItsCommittedBytesAndBack() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (String name : cases()) {
            tests.add(DynamicTest.dynamicTest(name, () -> {
                AgUiEvent fromJson = decodeJson(resource("json/" + name));
                byte[] committed = resource("bytes/" + name + ".bin");

                byte[] ours = ProtoCodec.encode(EventCodec.encode(fromJson));
                assertThat(HexFormat.of().formatHex(ours)).as("encoded bytes").isEqualTo(HexFormat.of().formatHex(committed));

                List<String> warnings = new ArrayList<>();
                AgUiEvent fromBytes = EventCodec.decode(new Decoder(warnings), ProtoCodec.decode(committed, warnings));
                // Compared as JSON trees: an object's member order is not part of the event.
                assertThat(Json.parse(Json.write(EventCodec.encode(fromBytes)))).as("decoded event")
                        .isEqualTo(Json.parse(Json.write(EventCodec.encode(fromJson))));
                assertThat(warnings).isEmpty();
            }));
        }
        return tests.stream();
    }

    private static AgUiEvent decodeJson(byte[] json) {
        List<String> warnings = new ArrayList<>();
        AgUiEvent event = EventCodec.decode(new Decoder(warnings), Json.parse(json));
        assertThat(warnings).as("the fixture decodes cleanly").isEmpty();
        return event;
    }

    private static List<String> cases() throws IOException {
        List<String> names = new ArrayList<>();
        for (String line : new String(resource("SHA256SUMS"), StandardCharsets.UTF_8).split("\n")) {
            String path = line.split("  ", 2)[1];
            if (path.startsWith("json/")) {
                names.add(path.substring("json/".length()));
            }
        }
        return names;
    }

    static byte[] resource(String path) throws IOException {
        try (InputStream in = ProtoCorpusTest.class.getResourceAsStream(CORPUS + path)) {
            if (in == null) {
                throw new IOException("missing test resource " + CORPUS + path);
            }
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
