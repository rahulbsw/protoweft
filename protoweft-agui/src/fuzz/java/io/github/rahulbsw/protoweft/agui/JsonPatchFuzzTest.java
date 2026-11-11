package io.github.rahulbsw.protoweft.agui;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;

import java.util.List;

import static io.github.rahulbsw.protoweft.agui.FuzzJson.obj;

/**
 * Fuzzes JSON Patch, which a client applies to its state for every {@code STATE_DELTA} and
 * {@code ACTIVITY_DELTA} an agent sends. Input: a document and a patch (two lines in raw mode). A
 * patch is rejected as malformed ({@link AgUiFormatException}) or applies or fails
 * ({@link JsonPatch.PatchException}); nothing else may escape, and a failed patch leaves the
 * document exactly as it was.
 */
class JsonPatchFuzzTest {

    private static final List<String> WORDS = List.of("op", "path", "from", "value", "add", "remove", "replace",
            "move", "copy", "test", "", "/", "/a", "/a/b", "/a/0", "/a/-", "/~0", "/~1", "/0", "a", "b", "-", "01");

    private static final List<Object> TEMPLATES = List.of(
            obj("a", obj("b", List.of(1, 2, 3)), "c", "x"),
            List.of(obj("op", "add", "path", "/a/b/-", "value", 4), obj("op", "move", "from", "/c", "path", "/d")),
            List.of(obj("op", "test", "path", "/a/b/0", "value", 1), obj("op", "remove", "path", "/a"),
                    obj("op", "copy", "from", "", "path", "/e")));

    @DictionaryFile(resourcePath = "json.dict")
    @FuzzTest(maxDuration = "2m")
    void patch(byte[] input) {
        List<byte[]> docs = FuzzJson.documents(input, WORDS, TEMPLATES);
        if (docs.size() < 2) {
            return;
        }
        Object document;
        List<Object> patch;
        try {
            document = Json.parse(docs.get(0));
            if (!(Json.parse(docs.get(1)) instanceof List<?> ops)) {
                return;
            }
            patch = JsonPatch.read(new Decoder(), new java.util.ArrayList<>(ops), "patch");
        } catch (AgUiFormatException rejected) {
            return;
        }
        String before = Json.write(document);
        try {
            JsonPatch.apply(document, patch);
        } catch (JsonPatch.PatchException failed) {
            // expected for patches that do not apply
        }
        if (!before.equals(Json.write(document))) {
            throw new IllegalStateException("applying a patch changed the input document");
        }
    }
}
