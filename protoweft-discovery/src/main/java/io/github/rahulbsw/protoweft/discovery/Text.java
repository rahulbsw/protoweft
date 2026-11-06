package io.github.rahulbsw.protoweft.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Tokenizer shared by indexing and querying: splits identifiers, lowercases, drops stopwords. */
final class Text {

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "by", "at", "from", "is",
            "are", "be", "it", "this", "that", "as", "into", "via", "me", "my", "i", "you", "your", "please",
            "can", "could", "would", "should", "do", "does", "what", "which", "how", "some", "any", "all");

    private Text() {
    }

    /**
     * Tokens of {@code text}: camelCase, snake_case, kebab-case and dotted identifiers are split;
     * tokens are lowercased, stopwords dropped, and a plural {@code s} trimmed.
     */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder word = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                boolean camelBoundary = Character.isUpperCase(c) && word.length() > 0
                        && (Character.isLowerCase(prev) || Character.isDigit(prev)
                        || (i + 1 < text.length() && Character.isLowerCase(text.charAt(i + 1))
                        && Character.isUpperCase(prev)));
                if (camelBoundary) {
                    emit(word, out);
                }
                word.append(c);
            } else {
                emit(word, out);
            }
            prev = c;
        }
        emit(word, out);
        return out;
    }

    private static void emit(StringBuilder word, List<String> out) {
        if (word.isEmpty()) {
            return;
        }
        String token = word.toString().toLowerCase(Locale.ROOT);
        word.setLength(0);
        if (STOPWORDS.contains(token)) {
            return;
        }
        if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us")
                && !token.endsWith("is")) {
            if (token.endsWith("ies")) {
                token = token.substring(0, token.length() - 3) + "y";
            } else if (token.endsWith("sses") || token.endsWith("ches") || token.endsWith("shes")
                    || token.endsWith("xes")) {
                token = token.substring(0, token.length() - 2);
            } else {
                token = token.substring(0, token.length() - 1);
            }
        }
        out.add(token);
    }

    /** First sentence of {@code description}, at most {@code max} characters. */
    static String summary(String description, int max) {
        if (description == null) {
            return "";
        }
        String flat = description.strip().replaceAll("\\s+", " ");
        int end = -1;
        for (int i = 0; i < flat.length() - 1; i++) {
            char c = flat.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && flat.charAt(i + 1) == ' ') {
                end = i + 1;
                break;
            }
        }
        String sentence = end > 0 ? flat.substring(0, end) : flat;
        return sentence.length() <= max ? sentence : sentence.substring(0, max - 1).strip() + "…";
    }
}
