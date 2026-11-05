package io.agentweave.utcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UTCP variable substitution, after {@code utcp/implementations/default_variable_substitutor.py}:
 * {@code ${VAR}} / {@code $VAR} in every string of a call template, namespaced by manual name,
 * resolved from configured variables, then dotenv files, then the environment.
 */
final class Variables {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}|\\$([a-zA-Z0-9_]+)");
    private static final Pattern JSON_REF = Pattern.compile("\\$ref(?![a-zA-Z0-9_])");

    private final Map<String, String> config;
    private final List<Path> dotenvFiles;
    private final UnaryOperator<String> environment;

    private Variables(Map<String, String> config, List<Path> dotenvFiles, UnaryOperator<String> environment) {
        this.config = config;
        this.dotenvFiles = List.copyOf(dotenvFiles);
        this.environment = environment;
    }

    /**
     * Like the reference client's {@code create()}, configured variable values are themselves
     * substituted (un-namespaced) from the loaders and the environment.
     */
    static Variables create(Map<String, String> config, List<Path> dotenvFiles, UnaryOperator<String> environment) {
        Variables withoutConfig = new Variables(Map.of(), dotenvFiles, environment);
        Map<String, String> resolved = new LinkedHashMap<>();
        config.forEach((k, v) -> resolved.put(k, (String) withoutConfig.substitute(v, null)));
        return new Variables(Map.copyOf(resolved), dotenvFiles, environment);
    }

    /** The lookup key for {@code name} in a manual's namespace, e.g. {@code my_api} + {@code KEY} → {@code my__api_KEY}. */
    static String namespaced(String namespace, String name) {
        return namespace == null || namespace.isEmpty() ? name : namespace.replace("_", "__") + "_" + name;
    }

    /** Recursively substitutes every string in maps/lists; other values are returned unchanged. */
    Object substitute(Object value, String namespace) {
        if (value instanceof String s) {
            if (JSON_REF.matcher(s).find()) {
                return s;
            }
            Matcher m = VARIABLE.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String name = m.group(1) != null ? m.group(1) : m.group(2);
                m.appendReplacement(out, Matcher.quoteReplacement(lookup(namespaced(namespace, name))));
            }
            m.appendTail(out);
            return out.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), substitute(v, namespace)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(substitute(v, namespace)));
            return out;
        }
        return value;
    }

    private String lookup(String key) {
        if (config.containsKey(key)) {
            return config.get(key);
        }
        for (Path file : dotenvFiles) {
            String v = dotenv(file).get(key);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        String env = environment.apply(key);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.UTCP, "Variable " + key
                + " referenced in provider configuration not found. Please add it to the environment variables"
                + " or to your UTCP configuration.");
    }

    /** Reads a .env file on every lookup, as python-dotenv's {@code dotenv_values} does; a missing file is empty. */
    static Map<String, String> dotenv(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Map.of();
        } catch (IOException e) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.UTCP, "cannot read dotenv file " + file, e);
        }
        return parseDotenv(content);
    }

    /** KEY=VALUE lines, optional {@code export}, # comments, single (literal) and double (escaped) quotes. */
    static Map<String, String> parseDotenv(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : content.split("\\R")) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            if (s.startsWith("export ")) {
                s = s.substring(7).strip();
            }
            int eq = s.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = s.substring(0, eq).strip();
            String raw = s.substring(eq + 1).strip();
            values.put(key, value(raw));
        }
        return values;
    }

    private static String value(String raw) {
        if (raw.startsWith("'")) {
            int end = raw.indexOf('\'', 1);
            return end < 0 ? raw.substring(1) : raw.substring(1, end);
        }
        if (raw.startsWith("\"")) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && i + 1 < raw.length()) {
                    char n = raw.charAt(++i);
                    out.append(switch (n) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> n;
                    });
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        Matcher comment = Pattern.compile("\\s#").matcher(raw);
        return (comment.find() ? raw.substring(0, comment.start()) : raw).strip();
    }
}
