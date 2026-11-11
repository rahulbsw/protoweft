package io.github.rahulbsw.protoweft.oauth;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtoweftException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static io.github.rahulbsw.protoweft.oauth.FuzzJson.obj;

/**
 * Fuzzes RFC 9728 Protected Resource Metadata documents (a resource server's answer; raw or
 * generated JSON, see {@link FuzzJson}): {@link ProtectedResourceMetadata#parse} either returns a
 * document or rejects it with a {@link ProtoweftException} ({@code PROTOCOL}, or
 * {@code CONFIGURATION} for a resource the builder refuses). An accepted document has a canonical
 * resource, a well-known URI, and survives a {@code toJson} round trip.
 *
 * <p>Regression mode (the default) runs the seeds under
 * {@code ProtectedResourceMetadataFuzzTestInputs/}; fuzz with {@code JAZZER_FUZZ=1} (see
 * CONTRIBUTING.md "Fuzzing").
 */
class ProtectedResourceMetadataFuzzTest {

    private static final Set<ErrorCategory> REJECTIONS = Set.of(ErrorCategory.PROTOCOL, ErrorCategory.CONFIGURATION);
    /** Keys and values that structured mode builds documents from. */
    private static final List<String> WORDS = List.of("resource", "authorization_servers", "scopes_supported",
            "bearer_methods_supported", "resource_documentation", "resource_name", "header", "body",
            "https://mcp.example.com/mcp", "https://auth.example.com", "HTTP://MCP.EXAMPLE.COM:80/a/", "mcp:read",
            "http://[::1]:8080/x?q=1", "https://u@h/", "https://h/#f", "urn:x", "", " ", "%", "https://h/a b");
    /** Valid documents that structured mode edits. */
    private static final List<Object> TEMPLATES = List.of(
            obj("resource", "https://mcp.example.com/mcp", "authorization_servers", List.of("https://auth.example.com"),
                    "scopes_supported", List.of("mcp:read", "mcp:write"), "bearer_methods_supported", List.of("header"),
                    "resource_name", "MCP"),
            obj("resource", "http://127.0.0.1:8080/", "authorization_servers",
                    List.of("https://a.example.com/tenant", "https://b.example.com")));

    @DictionaryFile(resourcePath = "json.dict")
    @FuzzTest(maxDuration = "2m")
    void document(byte[] input) {
        String json = new String(FuzzJson.document(input, WORDS, TEMPLATES), StandardCharsets.UTF_8);
        ProtectedResourceMetadata prm;
        try {
            prm = ProtectedResourceMetadata.parse(json);
        } catch (ProtoweftException e) {
            if (!REJECTIONS.contains(e.category())) {
                throw e;
            }
            return;
        }
        check(Uris.canonical(prm.resource()).equals(prm.resource()), "the resource is canonical");
        prm.metadataUri();
        ProtectedResourceMetadata again = ProtectedResourceMetadata.parse(prm.toJson());
        check(again.resource().equals(prm.resource())
                && again.authorizationServers().equals(prm.authorizationServers())
                && again.scopesSupported().equals(prm.scopesSupported())
                && again.bearerMethodsSupported().equals(prm.bearerMethodsSupported()), "toJson round trip");
    }


    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("accepted metadata violates: " + what);
        }
    }
}
