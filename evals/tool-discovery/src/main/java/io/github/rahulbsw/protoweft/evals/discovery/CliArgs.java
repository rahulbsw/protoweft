package io.github.rahulbsw.protoweft.evals.discovery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Minimal {@code command --flag value --switch} parser. Flags may repeat; list-valued flags also
 * accept comma-separated values. Every flag must be consumed; unknown flags are an error.
 */
public final class CliArgs {

    private final String command;
    private final Map<String, List<String>> flags = new LinkedHashMap<>();
    private final Set<String> used = new TreeSet<>();

    public CliArgs(String[] args) {
        List<String> a = Arrays.asList(args);
        command = a.isEmpty() ? "help" : a.get(0);
        for (int i = 1; i < a.size(); i++) {
            String t = a.get(i);
            if (!t.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + t);
            }
            String name = t.substring(2);
            String value = "true";
            int eq = name.indexOf('=');
            if (eq > 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            } else if (i + 1 < a.size() && !a.get(i + 1).startsWith("--")) {
                value = a.get(++i);
            }
            flags.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
    }

    public String command() {
        return command;
    }

    public String string(String name, String def) {
        used.add(name);
        List<String> v = flags.get(name);
        return v == null ? def : v.get(v.size() - 1);
    }

    public boolean flag(String name) {
        return Boolean.parseBoolean(string(name, "false"));
    }

    public int integer(String name, int def) {
        return Integer.parseInt(string(name, String.valueOf(def)));
    }

    public long longValue(String name, long def) {
        return Long.parseLong(string(name, String.valueOf(def)));
    }

    public double decimal(String name, double def) {
        return Double.parseDouble(string(name, String.valueOf(def)));
    }

    /** Optional number; {@code none} or absent gives {@code null}. */
    public Double optionalDecimal(String name) {
        String v = string(name, "none");
        return v.equalsIgnoreCase("none") ? null : Double.valueOf(v);
    }

    /** Values of a repeatable flag, each split on commas. */
    public List<String> list(String name, String def) {
        used.add(name);
        List<String> raw = flags.getOrDefault(name, def == null ? List.of() : List.of(def));
        List<String> out = new ArrayList<>();
        raw.forEach(v -> Arrays.stream(v.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(out::add));
        return out;
    }

    /** Values of a repeatable flag, not split (for values that contain commas). */
    public List<String> repeated(String name, List<String> def) {
        used.add(name);
        return flags.getOrDefault(name, def);
    }

    public List<Integer> integers(String name, String def) {
        return list(name, def).stream().map(s -> s.equalsIgnoreCase("all") ? 0 : Integer.parseInt(s)).toList();
    }

    /** Fails on flags that no code path read. Call after all reads. */
    public void rejectUnknown() {
        Set<String> unknown = new TreeSet<>(flags.keySet());
        unknown.removeAll(used);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown option(s): --" + String.join(", --", unknown));
        }
    }
}
