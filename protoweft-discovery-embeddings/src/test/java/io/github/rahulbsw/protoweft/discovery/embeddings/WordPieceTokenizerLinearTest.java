package io.github.rahulbsw.protoweft.discovery.embeddings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for a fuzzing-review finding: text made of many literal special tokens took quadratic
 * time (400K characters of "[SEP]": 21 s). A remote tool description must not be able to burn CPU.
 */
class WordPieceTokenizerLinearTest {

    @Test
    void manyLiteralSpecialTokensTokenizeInLinearTime() {
        WordPieceTokenizer tokenizer = References.javaTokenizer();
        String text = "[SEP]".repeat(80_000) + " tail";   // 400K characters

        long t0 = System.nanoTime();
        int[] ids = tokenizer.tokenIds(text);
        long millis = (System.nanoTime() - t0) / 1_000_000;

        assertThat(ids).hasSize(80_001);
        assertThat(ids[0]).isEqualTo(102);   // [SEP]
        assertThat(millis).as("tokenizing took %d ms", millis).isLessThan(2_000);
    }
}
