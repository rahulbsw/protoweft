package io.agentweave.utcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code tag_and_description_word_match} from {@code utcp/implementations/tag_search.py}. */
final class ToolSearch {

    static final double TAG_WEIGHT = 3;
    static final double DESCRIPTION_WEIGHT = 1;
    private static final Pattern WORD = Pattern.compile("\\w+", Pattern.UNICODE_CHARACTER_CLASS);

    private ToolSearch() {
    }

    private record Scored(Manual.Tool tool, double score) {
    }

    /** Tools sorted by score, highest first; zero-score tools are kept; ties keep manual order. */
    static List<Manual.Tool> search(List<Manual.Tool> tools, String query, int limit, List<String> anyOfTagsRequired) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        String queryLower = query.toLowerCase(Locale.ROOT);
        Set<String> queryWords = words(queryLower);
        List<Manual.Tool> candidates = tools;
        if (anyOfTagsRequired != null && !anyOfTagsRequired.isEmpty()) {
            List<String> required = anyOfTagsRequired.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
            candidates = tools.stream()
                    .filter(t -> t.tags().stream().anyMatch(tag -> required.contains(tag.toLowerCase(Locale.ROOT))))
                    .toList();
        }
        List<Scored> scored = new ArrayList<>();
        for (Manual.Tool tool : candidates) {
            double score = 0;
            for (String tag : tool.tags()) {
                String tagLower = tag.toLowerCase(Locale.ROOT);
                if (queryLower.contains(tagLower)) {
                    score += TAG_WEIGHT;
                    continue;
                }
                for (String word : words(tagLower)) {
                    if (queryWords.contains(word)) {
                        score += TAG_WEIGHT;
                        break;
                    }
                }
            }
            if (!tool.description().isEmpty()) {
                for (String word : words(tool.description().toLowerCase(Locale.ROOT))) {
                    if (queryWords.contains(word) && word.codePointCount(0, word.length()) > 2) {
                        score += DESCRIPTION_WEIGHT;
                    }
                }
            }
            scored.add(new Scored(tool, score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream().limit(limit).map(Scored::tool).toList();
    }

    private static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            words.add(m.group());
        }
        return words;
    }
}
