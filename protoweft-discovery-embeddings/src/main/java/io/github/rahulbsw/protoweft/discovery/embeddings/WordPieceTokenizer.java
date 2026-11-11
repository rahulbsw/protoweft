package io.github.rahulbsw.protoweft.discovery.embeddings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The BERT WordPiece tokenizer of uncased models (bert-base-uncased, bge, MiniLM cross-encoders),
 * with the behavior of HuggingFace {@code tokenizers}' {@code BertNormalizer} (text cleaning,
 * CJK spacing, accent stripping, lowercasing), {@code BertPreTokenizer} (whitespace and
 * punctuation splitting) and {@code WordPiece} model, as configured in those models'
 * {@code tokenizer.json}.
 *
 * <p>The special tokens {@code [PAD] [UNK] [CLS] [SEP] [MASK]} are matched literally (case
 * sensitive) in the input before normalization, as HuggingFace does. {@link #encode} gives
 * {@code [CLS] A [SEP]}; {@link #encodePair} gives {@code [CLS] A [SEP] B [SEP]} with token type
 * 0 for the first part and 1 for the second, truncated longest-first.
 *
 * <p>Immutable and thread-safe.
 */
public final class WordPieceTokenizer {

    private static final String CONTINUATION = "##";
    private static final int MAX_INPUT_CHARS_PER_WORD = 100;
    private static final List<String> SPECIAL_TOKENS = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]");

    private final String[] tokens;
    private final Map<String, Integer> ids;
    private final List<String> specialTokens;
    private final int unkId;
    private final int clsId;
    private final int sepId;

    private WordPieceTokenizer(List<String> vocabulary) {
        this.tokens = vocabulary.toArray(String[]::new);
        this.ids = new HashMap<>(tokens.length * 2);
        for (int i = 0; i < tokens.length; i++) {
            String token = Objects.requireNonNull(tokens[i], "vocabulary entry " + i);
            if (ids.putIfAbsent(token, i) != null) {
                throw new IllegalArgumentException("Duplicate vocabulary entry '" + token + "' at " + i);
            }
        }
        this.unkId = requiredId("[UNK]");
        this.clsId = requiredId("[CLS]");
        this.sepId = requiredId("[SEP]");
        this.specialTokens = SPECIAL_TOKENS.stream().filter(ids::containsKey).toList();
    }

    /** A tokenizer over {@code vocabulary}, where a token's id is its index. */
    public static WordPieceTokenizer of(List<String> vocabulary) {
        return new WordPieceTokenizer(List.copyOf(vocabulary));
    }

    /**
     * Reads a BERT {@code vocab.txt} (UTF-8, one token per line, the line number is the id) and
     * closes the stream.
     */
    public static WordPieceTokenizer read(InputStream vocabTxt) {
        Objects.requireNonNull(vocabTxt, "vocabTxt");
        List<String> vocabulary = new ArrayList<>(32_000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(vocabTxt, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                vocabulary.add(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the vocabulary", e);
        }
        return new WordPieceTokenizer(vocabulary);
    }

    /** The number of tokens in the vocabulary. */
    public int vocabularySize() {
        return tokens.length;
    }

    /** The token with this id. */
    public String token(int id) {
        return tokens[id];
    }

    /** The id of {@code token}, or -1 if it is not in the vocabulary. */
    public int id(String token) {
        return ids.getOrDefault(token, -1);
    }

    /** The word pieces of {@code text}, without {@code [CLS]} and {@code [SEP]}. */
    public List<String> tokenize(String text) {
        int[] pieces = tokenIds(text);
        List<String> out = new ArrayList<>(pieces.length);
        for (int id : pieces) {
            out.add(tokens[id]);
        }
        return out;
    }

    /** The token ids of {@code text}, without {@code [CLS]} and {@code [SEP]}, never truncated. */
    public int[] tokenIds(String text) {
        Objects.requireNonNull(text, "text");
        IntList out = new IntList(text.length() / 3 + 4);
        int start = 0;
        // Next occurrence of each special token, searched again only once `start` passes it, so
        // each candidate scans the text once in total (linear, however many matches).
        int[] next = new int[specialTokens.size()];
        for (int c = 0; c < next.length; c++) {
            next[c] = text.indexOf(specialTokens.get(c));
        }
        while (start < text.length()) {
            int at = -1;
            String special = null;
            for (int c = 0; c < next.length; c++) {
                if (next[c] >= 0 && next[c] < start) {
                    next[c] = text.indexOf(specialTokens.get(c), start);
                }
                int i = next[c];
                if (i >= 0 && (at < 0 || i < at)) {
                    at = i;
                    special = specialTokens.get(c);
                }
            }
            int end = at < 0 ? text.length() : at;
            if (end > start) {
                wordPieces(normalize(text.substring(start, end)), out);
            }
            if (special == null) {
                break;
            }
            out.add(ids.get(special));
            start = at + special.length();
        }
        return out.toArray();
    }

    /**
     * {@code [CLS] text [SEP]}, keeping the first {@code maxLength - 2} word pieces.
     *
     * @param maxLength the most tokens in the result, special tokens included; at least 2
     */
    public Encoding encode(String text, int maxLength) {
        if (maxLength < 2) {
            throw new IllegalArgumentException("maxLength must be >= 2: " + maxLength);
        }
        int[] pieces = tokenIds(text);
        return wrap(pieces, 0, Math.min(pieces.length, maxLength - 2));
    }

    /**
     * {@code [CLS] first [SEP] second [SEP]}, truncated longest-first as HuggingFace
     * {@code tokenizers} does: with {@code n = maxLength - 3}, if both parts are longer than
     * {@code n / 2}, the shorter keeps {@code n / 2} pieces and the longer {@code n / 2 + n % 2};
     * otherwise only the longer part is cut, to {@code n} minus the shorter's length.
     *
     * @param maxLength the most tokens in the result, special tokens included; at least 3
     */
    public Encoding encodePair(String first, String second, int maxLength) {
        if (maxLength < 3) {
            throw new IllegalArgumentException("maxLength must be >= 3: " + maxLength);
        }
        int[] a = tokenIds(first);
        int[] b = tokenIds(second);
        int[] kept = longestFirst(a.length, b.length, maxLength - 3);
        int n1 = kept[0];
        int n2 = kept[1];
        int[] ids = new int[n1 + n2 + 3];
        int[] types = new int[ids.length];
        ids[0] = clsId;
        System.arraycopy(a, 0, ids, 1, n1);
        ids[n1 + 1] = sepId;
        System.arraycopy(b, 0, ids, n1 + 2, n2);
        ids[ids.length - 1] = sepId;
        Arrays.fill(types, n1 + 2, types.length, 1);
        return new Encoding(ids, types);
    }

    /** Lengths kept by longest-first truncation of two parts to {@code max} pieces in total. */
    static int[] longestFirst(int firstLength, int secondLength, int max) {
        if (firstLength + secondLength <= max) {
            return new int[] {firstLength, secondLength};
        }
        boolean swap = firstLength > secondLength;
        int n1 = swap ? secondLength : firstLength;
        int n2 = n1 > max ? n1 : Math.max(n1, max - n1);
        if (n1 + n2 > max) {
            n1 = max / 2;
            n2 = n1 + max % 2;
        }
        int keptFirst = Math.min(firstLength, swap ? n2 : n1);
        int keptSecond = Math.min(secondLength, swap ? n1 : n2);
        return new int[] {keptFirst, keptSecond};
    }

    /** {@code [CLS] pieces[from..to) [SEP]}. */
    private Encoding wrap(int[] pieces, int from, int to) {
        int[] ids = new int[to - from + 2];
        ids[0] = clsId;
        System.arraycopy(pieces, from, ids, 1, to - from);
        ids[ids.length - 1] = sepId;
        return new Encoding(ids, new int[ids.length]);
    }

    /** BertNormalizer: clean text, pad CJK, strip accents, lowercase. */
    static String normalize(String text) {
        StringBuilder cleaned = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            // A lone surrogate becomes U+FFFD in UTF-8, which cleaning removes.
            if (BertCharacters.isRemovedByCleaning(c) || (c >= 0xD800 && c <= 0xDFFF)) {
                continue;
            }
            if (BertCharacters.isWhitespace(c)) {
                cleaned.append(' ');
            } else if (BertCharacters.isChinese(c)) {
                cleaned.append(' ').appendCodePoint(c).append(' ');
            } else {
                cleaned.appendCodePoint(c);
            }
        }
        String decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); ) {
            int c = decomposed.codePointAt(i);
            i += Character.charCount(c);
            if (BertCharacters.isNonspacingMark(c)) {
                continue;
            }
            if (c == 0x130) {
                out.append("i\u0307"); // the one unconditional multi-character lowercase mapping
            } else {
                out.appendCodePoint(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /** BertPreTokenizer (whitespace, then isolated punctuation), then WordPiece on each word. */
    private void wordPieces(String normalized, IntList out) {
        int wordStart = -1;
        for (int i = 0; i < normalized.length(); ) {
            int c = normalized.codePointAt(i);
            int next = i + Character.charCount(c);
            if (BertCharacters.isWhitespace(c)) {
                if (wordStart >= 0) {
                    word(normalized.substring(wordStart, i), out);
                    wordStart = -1;
                }
            } else if (BertCharacters.isPunctuation(c)) {
                if (wordStart >= 0) {
                    word(normalized.substring(wordStart, i), out);
                    wordStart = -1;
                }
                word(normalized.substring(i, next), out);
            } else if (wordStart < 0) {
                wordStart = i;
            }
            i = next;
        }
        if (wordStart >= 0) {
            word(normalized.substring(wordStart), out);
        }
    }

    /** Greedy longest-match-first; an over-long word or one with an unmatched rest is [UNK]. */
    private void word(String word, IntList out) {
        if (word.codePointCount(0, word.length()) > MAX_INPUT_CHARS_PER_WORD) {
            out.add(unkId);
            return;
        }
        int mark = out.size();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            int found = -1;
            while (start < end) {
                String piece = word.substring(start, end);
                Integer id = ids.get(start > 0 ? CONTINUATION + piece : piece);
                if (id != null) {
                    found = id;
                    break;
                }
                end = word.offsetByCodePoints(end, -1);
            }
            if (found < 0) {
                out.truncate(mark);
                out.add(unkId);
                return;
            }
            out.add(found);
            start = end;
        }
    }

    private int requiredId(String token) {
        Integer id = ids.get(token);
        if (id == null) {
            throw new IllegalArgumentException("The vocabulary has no " + token + " token");
        }
        return id;
    }

    /** Token ids and token type ids of one model input; no padding, so every position is attended. */
    public static final class Encoding {

        private final int[] ids;
        private final int[] typeIds;

        Encoding(int[] ids, int[] typeIds) {
            this.ids = ids;
            this.typeIds = typeIds;
        }

        /** The number of tokens, special tokens included. */
        public int length() {
            return ids.length;
        }

        /** The token ids (a copy). */
        public int[] ids() {
            return ids.clone();
        }

        /** The token type (segment) ids: 0 for the first part, 1 for the second (a copy). */
        public int[] typeIds() {
            return typeIds.clone();
        }

        int[] idsUnsafe() {
            return ids;
        }

        int[] typeIdsUnsafe() {
            return typeIds;
        }

        @Override
        public String toString() {
            return "Encoding" + Arrays.toString(ids);
        }
    }

    /** A growable int array. */
    private static final class IntList {

        private int[] values;
        private int size;

        IntList(int capacity) {
            values = new int[Math.max(capacity, 8)];
        }

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        int size() {
            return size;
        }

        void truncate(int newSize) {
            size = newSize;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
