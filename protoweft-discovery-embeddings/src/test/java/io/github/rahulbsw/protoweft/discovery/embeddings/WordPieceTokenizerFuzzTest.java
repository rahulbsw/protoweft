package io.github.rahulbsw.protoweft.discovery.embeddings;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Fuzzes the WordPiece tokenizer on the real bge vocabulary with any text: tool names and
 * descriptions from remote servers end up here. Whatever the text (lone surrogates, control and
 * private-use characters, literal special tokens, very long words), tokenizing never throws, every
 * id is in the vocabulary, {@code encode} and {@code encodePair} keep their {@code [CLS]}/{@code [SEP]}
 * layout and token types, and truncation never exceeds {@code maxLength}, keeps the first pieces and
 * follows the documented longest-first rule. Besides fixed lengths, {@code maxLength} comes from the
 * input's last byte.
 *
 * <p>Input decoding keeps seeds readable: UTF-8, except that an input starting with {@code 0xFF}
 * (never valid UTF-8) is UTF-16BE, which reaches lone surrogates. For pairs, the text splits at the
 * first U+0000 (the tokenizer drops NUL anyway).
 *
 * <p>Regression mode (the default) runs the seeds under {@code WordPieceTokenizerFuzzTestInputs/};
 * fuzz with {@code JAZZER_FUZZ=1} (see CONTRIBUTING.md "Fuzzing").
 */
class WordPieceTokenizerFuzzTest {

    private static final WordPieceTokenizer BGE = References.javaTokenizer();
    private static final int CLS = BGE.id("[CLS]");
    private static final int SEP = BGE.id("[SEP]");

    @FuzzTest(maxDuration = "2m")
    void encode(byte[] input) {
        String text = decode(input);
        int[] ids = BGE.tokenIds(text);
        inVocabulary(ids);
        check(BGE.tokenize(text).size() == ids.length, "tokenize and tokenIds agree");
        for (int max : new int[] {2, 2 + lastByte(input) % 64, 512}) {
            WordPieceTokenizer.Encoding encoding = BGE.encode(text, max);
            int[] enc = encoding.ids();
            int kept = Math.min(ids.length, max - 2);
            check(enc.length == kept + 2 && enc[0] == CLS && enc[enc.length - 1] == SEP,
                    "encode is [CLS] pieces [SEP] within maxLength " + max);
            check(Arrays.equals(Arrays.copyOfRange(enc, 1, kept + 1), Arrays.copyOf(ids, kept)),
                    "encode keeps the first pieces");
            check(Arrays.stream(encoding.typeIds()).allMatch(t -> t == 0), "single text is type 0");
        }
    }

    @FuzzTest(maxDuration = "2m")
    void encodePair(byte[] input) {
        String text = decode(input);
        int nul = text.indexOf('\0');
        String first = nul < 0 ? text : text.substring(0, nul);
        String second = nul < 0 ? "" : text.substring(nul + 1);
        checkPair(first, second, 3, 3 + lastByte(input) % 64);
    }

    /**
     * The same invariants with structured input: two independent strings (any UTF-16, lone
     * surrogates included) and any {@code maxLength} from 3 to 600, beyond the byte-level target's
     * 3..66.
     */
    @FuzzTest(maxDuration = "2m")
    void encodePairStructured(FuzzedDataProvider data) {
        int max = data.consumeInt(3, 600);
        String first = data.consumeString(2_000);
        String second = data.consumeRemainingAsString();
        checkPair(first, second, max);
    }

    private static void checkPair(String first, String second, int... maxLengths) {
        int[] a = BGE.tokenIds(first);
        int[] b = BGE.tokenIds(second);
        for (int max : maxLengths) {
            WordPieceTokenizer.Encoding enc = BGE.encodePair(first, second, max);
            int[] ids = enc.ids();
            int[] types = enc.typeIds();
            inVocabulary(ids);
            int[] kept = keptByLongestFirst(a.length, b.length, max - 3);
            int n1 = kept[0];
            int sep = n1 + 1;
            check(ids.length <= max && ids.length == types.length && ids.length == kept[0] + kept[1] + 3,
                    "pair fits maxLength " + max + " and truncates only what it must");
            check(ids[0] == CLS && ids[sep] == SEP && ids[ids.length - 1] == SEP, "pair layout");
            check(Arrays.equals(Arrays.copyOfRange(ids, 1, sep), Arrays.copyOf(a, n1))
                    && Arrays.equals(Arrays.copyOfRange(ids, sep + 1, ids.length - 1), Arrays.copyOf(b, kept[1])),
                    "pair keeps the first pieces of each part");
            for (int i = 0; i < types.length; i++) {
                check(types[i] == (i <= sep ? 0 : 1), "token types");
            }
        }
    }

    /** UTF-8, or UTF-16BE (any code units, lone surrogates included) after a leading 0xFF. */
    private static String decode(byte[] input) {
        if (input.length > 0 && input[0] == (byte) 0xFF) {
            ByteBuffer buf = ByteBuffer.wrap(input, 1, (input.length - 1) & ~1);
            StringBuilder sb = new StringBuilder();
            while (buf.remaining() >= 2) {
                sb.append(buf.getChar());
            }
            return sb.toString();
        }
        return new String(input, StandardCharsets.UTF_8);
    }

    private static int lastByte(byte[] input) {
        return input.length == 0 ? 0 : input[input.length - 1] & 0xFF;
    }

    private static void inVocabulary(int[] ids) {
        for (int id : ids) {
            check(id >= 0 && id < BGE.vocabularySize(), "id " + id + " is in the vocabulary");
        }
    }

    /**
     * The documented longest-first rule, independently of the implementation: with {@code n} pieces
     * of room, if both parts are longer than {@code n / 2} the shorter (the first, on a tie) keeps
     * {@code n / 2} and the longer {@code n / 2 + n % 2}; otherwise only the longer is cut, to
     * {@code n} minus the shorter's length.
     */
    private static int[] keptByLongestFirst(int a, int b, int n) {
        if (a + b <= n) {
            return new int[] {a, b};
        }
        boolean firstIsShorter = a <= b;
        int shorter = firstIsShorter ? a : b;
        int keptShorter = shorter > n / 2 ? n / 2 : shorter;
        int keptLonger = shorter > n / 2 ? n / 2 + n % 2 : n - shorter;
        return firstIsShorter ? new int[] {keptShorter, keptLonger} : new int[] {keptLonger, keptShorter};
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("tokenizer violates: " + what);
        }
    }
}
