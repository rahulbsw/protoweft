package io.github.rahulbsw.protoweft.discovery.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The pure-Java tokenizer gives the same ids as HuggingFace's Rust {@code tokenizers} (through
 * DJL) over the bge {@code tokenizer.json}: whole sequences, single-text truncation, and pairs.
 */
class TokenizerParityTest {

    private static final WordPieceTokenizer JAVA = References.javaTokenizer();

    @Test
    void trickyTextsMatch() {
        List<String> texts = ParityCorpus.tricky();

        assertThat(mismatchesOfWholeSequences(texts)).isEmpty();
        assertThat(mismatchesOfModelInputs(texts)).isEmpty();
        System.out.printf("[parity] tokens, tricky texts: %d compared, 0 mismatches%n", texts.size());
    }

    @Test
    void evalDatasetTextsMatch() {
        Optional<List<String>> corpus = ParityCorpus.evalTexts();
        assumeTrue(corpus.isPresent(), "eval datasets not downloaded (" + ParityCorpus.DATA_PROPERTY + ")");
        List<String> texts = corpus.get();

        long start = System.nanoTime();
        List<String> whole = mismatchesOfWholeSequences(texts);
        List<String> inputs = mismatchesOfModelInputs(texts);
        long tokens = texts.stream().mapToLong(t -> JAVA.tokenIds(t).length).sum();

        System.out.printf("[parity] tokens, eval datasets: %d texts (%d word pieces) compared, %d whole-sequence"
                        + " and %d model-input mismatches (%d s)%n",
                texts.size(), tokens, whole.size(), inputs.size(), (System.nanoTime() - start) / 1_000_000_000);
        assertThat(whole).isEmpty();
        assertThat(inputs).isEmpty();
    }

    @Test
    void truncationOfSingleTextsAndPairsMatches() throws IOException {
        List<String> texts = new ArrayList<>(ParityCorpus.tricky());
        ParityCorpus.evalTexts().ifPresent(corpus -> texts.addAll(ParityCorpus.sample(corpus, 2000, 7)));
        List<String> mismatches = new ArrayList<>();
        int pairs = 0;
        for (int maxLength : new int[] {3, 4, 9, 16, 17, 64, 512}) {
            try (HuggingFaceTokenizer rust = truncatingTokenizer(maxLength)) {
                for (int i = 0; i < texts.size(); i++) {
                    String a = texts.get(i);
                    String b = texts.get((i * 7 + 3) % texts.size());
                    pairs++;
                    compare("single/" + maxLength, a, JAVA.encode(a, maxLength), rust.encode(a), mismatches);
                    compare("pair/" + maxLength, a + " ||| " + b, JAVA.encodePair(a, b, maxLength),
                            rust.encode(a, b), mismatches);
                }
            }
        }
        System.out.printf("[parity] truncation: %d single texts and %d pairs compared, %d mismatches%n",
                pairs, pairs, mismatches.size());
        assertThat(mismatches).isEmpty();
    }

    /** Untruncated word pieces, no special tokens. */
    private static List<String> mismatchesOfWholeSequences(List<String> texts) {
        HuggingFaceTokenizer rust = References.untruncatedTokenizer();
        List<String> mismatches = new ArrayList<>();
        for (String text : texts) {
            long[] expected = rust.encode(text, false, false).getIds();
            long[] actual = Arrays.stream(JAVA.tokenIds(text)).asLongStream().toArray();
            if (!Arrays.equals(expected, actual)) {
                mismatches.add(describe(text, expected, actual));
            }
        }
        return mismatches;
    }

    /** What LangChain4j's tokenizer feeds the model (special tokens, truncated to 512). */
    private static List<String> mismatchesOfModelInputs(List<String> texts) {
        HuggingFaceTokenizer rust = References.langChain4jTokenizer();
        List<String> mismatches = new ArrayList<>();
        for (String text : texts) {
            compare("model-input", text, JAVA.encode(text, 512), rust.encode(text), mismatches);
        }
        return mismatches;
    }

    private static void compare(String what, String text, WordPieceTokenizer.Encoding actual, Encoding expected,
            List<String> mismatches) {
        long[] ids = Arrays.stream(actual.ids()).asLongStream().toArray();
        long[] types = Arrays.stream(actual.typeIds()).asLongStream().toArray();
        if (!Arrays.equals(expected.getIds(), ids) || !Arrays.equals(expected.getTypeIds(), types)) {
            mismatches.add(what + ": " + describe(text, expected.getIds(), ids)
                    + " types " + Arrays.toString(expected.getTypeIds()) + " vs " + Arrays.toString(types));
        }
    }

    private static HuggingFaceTokenizer truncatingTokenizer(int maxLength) throws IOException {
        try (InputStream json = References.class.getClassLoader().getResourceAsStream(References.TOKENIZER_JSON)) {
            return HuggingFaceTokenizer.newInstance(json, Map.of(
                    "padding", "false", "truncation", "longest_first", "maxLength", String.valueOf(maxLength)));
        }
    }

    private static String describe(String text, long[] expected, long[] actual) {
        String shown = text.length() > 200 ? text.substring(0, 200) + "..." : text;
        return "'" + shown.replace("\n", "\\n") + "': rust " + abbreviate(expected) + " java " + abbreviate(actual);
    }

    private static String abbreviate(long[] ids) {
        String s = Arrays.toString(ids);
        return s.length() > 300 ? s.substring(0, 300) + "...(" + ids.length + ")" : s;
    }
}
