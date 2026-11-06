package io.github.rahulbsw.protoweft.mcp;

import java.util.List;

/**
 * Suggested values from {@code completion/complete}.
 *
 * @param values  the suggestions, at most 100
 * @param total   how many suggestions exist in all, or {@code null} if unknown
 * @param hasMore whether more suggestions exist than were returned
 */
public record McpCompletion(List<String> values, Integer total, boolean hasMore) {

    public McpCompletion {
        values = List.copyOf(values);
    }
}
