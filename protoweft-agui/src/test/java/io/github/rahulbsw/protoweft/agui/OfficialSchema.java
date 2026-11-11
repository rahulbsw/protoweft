package io.github.rahulbsw.protoweft.agui;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The official AG-UI 1.0 JSON Schema, vendored from {@code https://docs.ag-ui.com/spec/1.0/schema.json}
 * and pinned by checksum, as a test oracle: produced events and inputs must validate against it.
 */
final class OfficialSchema {

    static final String SOURCE = "https://docs.ag-ui.com/spec/1.0/schema.json";
    static final String SHA256 = "4b5c93226838a0e72d88e6c5df20633c686c49fcb75d9be2815c6cbf9e48e71a";

    private static final String TEXT = load();
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private OfficialSchema() {
    }

    /** Validation errors of {@code json} against definition {@code def} (for example {@code Event}). */
    static List<String> errors(String def, Object json) {
        Schema schema = SCHEMAS.computeIfAbsent(def, d -> SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(TEXT.replace("\"$ref\": \"#/$defs/Event\"", "\"$ref\": \"#/$defs/" + d + "\""),
                        InputFormat.JSON));
        return schema.validate(Json.write(json), InputFormat.JSON).stream().map(Error::toString).toList();
    }

    private static String load() {
        try (InputStream in = OfficialSchema.class.getResourceAsStream("schema-1.0.json")) {
            byte[] bytes = in.readAllBytes();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!sha.equals(SHA256)) {
                throw new IllegalStateException("vendored schema-1.0.json does not match its pinned SHA-256");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!text.contains("\"$ref\": \"#/$defs/Event\"")) {
                throw new IllegalStateException("schema root no longer refers to #/$defs/Event");
            }
            return text;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
