package io.agentweave.discovery.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The tokenizer's rules, on the bge (bert-base-uncased) vocabulary and on a toy one. */
class WordPieceTokenizerTest {

    private static final WordPieceTokenizer BGE = References.javaTokenizer();

    @Test
    void readsTheBgeVocabulary() {
        assertThat(BGE.vocabularySize()).isEqualTo(30522);
        assertThat(BGE.id("[PAD]")).isZero();
        assertThat(BGE.id("[UNK]")).isEqualTo(100);
        assertThat(BGE.id("[CLS]")).isEqualTo(101);
        assertThat(BGE.id("[SEP]")).isEqualTo(102);
        assertThat(BGE.token(103)).isEqualTo("[MASK]");
        assertThat(BGE.id("no such token")).isEqualTo(-1);
    }

    @Test
    void normalizesSplitsAndMatchesSpecialTokens() {
        assertThat(BGE.tokenIds("hello [MASK] world [cls]"))
                .containsExactly(7592, 103, 2088, 1031, 18856, 2015, 1033);
        // Accents stripped, CJK split per character, unknown emoji, NUL dropped, VT dropped.
        assertThat(BGE.tokenIds("héllo Wörld 中国 🤩 🧀 x\u0000y \u000Bz"))
                .containsExactly(7592, 2088, 1746, 1799, 100, 100, 1060, 2100, 1062);
        assertThat(BGE.tokenize("fs.read_file")).containsExactly("f", "##s", ".", "read", "_", "file");
        assertThat(BGE.tokenize("Ünaffable!")).containsExactly("una", "##ffa", "##ble", "!");
    }

    @Test
    void wordsOverOneHundredCharactersAreUnknown() {
        assertThat(BGE.tokenIds("a".repeat(100))).doesNotContain(100);
        assertThat(BGE.tokenIds("a".repeat(101))).containsExactly(100);
        assertThat(BGE.tokenIds("ok " + "😀".repeat(101) + " ok")).containsExactly(7929, 100, 7929);
    }

    @Test
    void encodeWrapsAndTruncates() {
        WordPieceTokenizer.Encoding encoding = BGE.encode("hello world again", 4);

        assertThat(encoding.ids()).containsExactly(101, 7592, 2088, 102);
        assertThat(encoding.typeIds()).containsExactly(0, 0, 0, 0);
        assertThat(BGE.encode("", 512).ids()).containsExactly(101, 102);
        assertThatThrownBy(() -> BGE.encode("x", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodePairMarksTheSecondSegment() {
        WordPieceTokenizer.Encoding encoding = BGE.encodePair("hello", "world again", 512);

        assertThat(encoding.ids()).containsExactly(101, 7592, 102, 2088, 2153, 102);
        assertThat(encoding.typeIds()).containsExactly(0, 0, 0, 1, 1, 1);
        assertThatThrownBy(() -> BGE.encodePair("a", "b", 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void longestFirstTruncation() {
        assertThat(WordPieceTokenizer.longestFirst(3, 3, 9)).containsExactly(3, 3);
        assertThat(WordPieceTokenizer.longestFirst(2, 20, 9)).containsExactly(2, 7);
        assertThat(WordPieceTokenizer.longestFirst(20, 2, 9)).containsExactly(7, 2);
        assertThat(WordPieceTokenizer.longestFirst(10, 10, 9)).containsExactly(4, 5);
        assertThat(WordPieceTokenizer.longestFirst(6, 7, 9)).containsExactly(4, 5);
        assertThat(WordPieceTokenizer.longestFirst(7, 6, 9)).containsExactly(5, 4);
        assertThat(WordPieceTokenizer.longestFirst(4, 6, 9)).containsExactly(4, 5);
        assertThat(WordPieceTokenizer.longestFirst(5, 5, 0)).containsExactly(0, 0);
    }

    @Test
    void greedyLongestMatchOnAToyVocabulary() {
        WordPieceTokenizer toy = WordPieceTokenizer.of(
                List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "un", "##aff", "##able", "una", "##ff", "!"));

        assertThat(toy.tokenize("unaffable!")).containsExactly("una", "##ff", "##able", "!");
        assertThat(toy.tokenize("unx")).containsExactly("[UNK]");
        assertThat(toy.tokenize("UN")).containsExactly("un");
        assertThatThrownBy(() -> WordPieceTokenizer.of(List.of("[CLS]", "[SEP]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[UNK]");
    }
}
