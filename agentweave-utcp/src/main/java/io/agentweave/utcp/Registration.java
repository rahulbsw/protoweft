package io.agentweave.utcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.auth.Credential;
import io.agentweave.core.auth.CredentialProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A registered manual: the reference client's {@code register_manual} (variable substitution on
 * the manual template, fetch/parse, allowed-protocol filtering) and its result.
 *
 * @param tools   registered tools by unprefixed name
 * @param skipped allowed tools whose call template type is not implemented, with the reason
 * @param dropped tools excluded by {@code allowed_communication_protocols}, with the reason
 */
record Registration(Variables variables, List<String> allowed, Map<String, Manual.Tool> tools,
                    Map<String, String> skipped, Map<String, String> dropped, String utcpVersion) {

    private static final System.Logger LOG = System.getLogger(UtcpClientConnector.class.getName());

    static Registration register(String manualName, Map<String, Object> manualTemplate, Variables vars,
                                 CredentialProvider credentials, HttpProtocol http) {
        Map<String, Object> template = Json.object(vars.substitute(manualTemplate, manualName));
        String type = (String) template.get("call_template_type");
        List<String> allowed;
        try {
            allowed = Manual.stringList(template.get("allowed_communication_protocols"), "allowed_communication_protocols");
        } catch (IllegalArgumentException e) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.UTCP, e.getMessage(), e);
        }
        allowed = allowed == null || allowed.isEmpty() ? List.of(type) : allowed;
        Manual manual = switch (type) {
            case "http" -> discover(httpTemplate(template, ErrorCategory.CONFIGURATION), credentials, http);
            case "text" -> local(parseLocal(configString(template, "content", "TextCallTemplate")), "text manual");
            case "file" -> local(readFile(configString(template, "file_path", "FileCallTemplate")), "file manual");
            default -> throw new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.UTCP,
                    "manual call template type '" + type + "' is not supported; supported: http, text, file");
        };
        Map<String, Manual.Tool> tools = new LinkedHashMap<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        Map<String, String> dropped = new LinkedHashMap<>();
        for (Manual.Tool tool : manual.tools()) {
            String local = localName(manualName, tool.name());
            String protocol = tool.callTemplateType();
            if (!allowed.contains(protocol)) {
                String reason = "Tool '" + tool.name() + "' uses communication protocol '" + protocol
                        + "' which is not in allowed protocols " + allowed + " for manual '" + manualName
                        + "'. Tool will not be registered.";
                LOG.log(System.Logger.Level.WARNING, reason);
                dropped.put(local, reason);
            } else if (!UtcpFeatures.CALLABLE_TEMPLATE_TYPES.contains(protocol)) {
                String reason = "call template type '" + protocol + "' is not supported by this connector";
                LOG.log(System.Logger.Level.INFO, "Skipping tool '" + tool.name() + "': " + reason);
                skipped.put(local, reason);
            } else {
                if (protocol.equals("http")) {
                    httpTemplate(tool.callTemplate(), ErrorCategory.PROTOCOL);
                } else if (!(tool.callTemplate().get("content") instanceof String)) {
                    throw new AgentWeaveException(ErrorCategory.PROTOCOL, ProtocolId.UTCP,
                            "Invalid TextCallTemplate for tool '" + tool.name() + "': 'content' is required");
                }
                tools.put(local, tool);
            }
        }
        return new Registration(vars, List.copyOf(allowed), Collections.unmodifiableMap(tools),
                Collections.unmodifiableMap(skipped), Collections.unmodifiableMap(dropped), manual.utcpVersion());
    }

    private static Manual discover(Manual.HttpTemplate template, CredentialProvider credentials, HttpProtocol http) {
        Map<String, String> headers = new LinkedHashMap<>();
        String target = template.url().replaceFirst("[?#].*$", "");
        for (Credential credential : credentials.credentialsFor(ProtocolId.UTCP, target)) {
            if (credential instanceof Credential.BearerToken bearer) {
                headers.put("Authorization", "Bearer " + bearer.token());
            } else if (credential instanceof Credential.NamedSecret secret) {
                headers.put(secret.name(), secret.value());
            }
        }
        HttpProtocol.Discovery d = http.discover(template, headers);
        Map<String, Object> data = Json.object(d.data());
        if (data == null || !data.containsKey("utcp_version") || !data.containsKey("tools")) {
            throw new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.UTCP, "'" + template.url()
                    + "' did not return a UTCP manual (no utcp_version/tools); OpenAPI conversion is not supported");
        }
        Manual manual = parse(data, ErrorCategory.PROTOCOL);
        UrlSecurity.rejectRemoteLoopbackToolUrls(d.finalUrl(), manual.tools());
        return manual;
    }

    private static Manual local(Object data, String what) {
        Map<String, Object> m = Json.object(data);
        if (m != null && (m.containsKey("openapi") || m.containsKey("swagger") || m.containsKey("paths"))) {
            throw new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.UTCP,
                    what + " is an OpenAPI specification; OpenAPI conversion is not supported");
        }
        return parse(data, ErrorCategory.CONFIGURATION);
    }

    private static Object parseLocal(String content) {
        try {
            return Json.parse(content);
        } catch (RuntimeException e) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.UTCP,
                    "manual content is not JSON (YAML manuals are not supported): " + e.getMessage(), e);
        }
    }

    private static Object readFile(String filePath) {
        Path path = Path.of(filePath).toAbsolutePath();
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            throw new AgentWeaveException(ErrorCategory.UNSUPPORTED, ProtocolId.UTCP,
                    "YAML manuals are not supported by this connector (" + path + ")");
        }
        if (!Files.exists(path)) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.UTCP, "Manual file not found: " + path);
        }
        try {
            return parseLocal(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.UTCP, "cannot read " + path, e);
        }
    }

    private static String configString(Map<String, Object> template, String key, String where) {
        try {
            return Manual.required(template, key, where);
        } catch (IllegalArgumentException e) {
            throw new AgentWeaveException(ErrorCategory.CONFIGURATION, ProtocolId.UTCP, e.getMessage(), e);
        }
    }

    private static Manual parse(Object data, ErrorCategory category) {
        try {
            return Manual.parse(data);
        } catch (IllegalArgumentException e) {
            throw new AgentWeaveException(category, ProtocolId.UTCP, e.getMessage(), e);
        }
    }

    static Manual.HttpTemplate httpTemplate(Map<String, Object> template, ErrorCategory category) {
        try {
            return Manual.HttpTemplate.parse(template);
        } catch (IllegalArgumentException e) {
            throw new AgentWeaveException(category, ProtocolId.UTCP, e.getMessage(), e);
        }
    }

    /** The reference client prefixes {@code <manual>.} unless present; we list without it. */
    static String localName(String manualName, String toolName) {
        return toolName.startsWith(manualName + ".") ? toolName.substring(manualName.length() + 1) : toolName;
    }
}
