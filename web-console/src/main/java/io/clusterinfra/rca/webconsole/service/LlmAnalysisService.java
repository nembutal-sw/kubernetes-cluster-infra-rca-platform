package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.analysis.LlmEvidenceCatalog;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmTestResponse;
import io.clusterinfra.rca.webconsole.security.SensitiveDataRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openaisdk.OpenAiSdkChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class LlmAnalysisService {
    private static final String PROMPT_VERSION = "llm-rca-analyzer/v2";
    private static final String TEST_PROMPT_VERSION = "llm-connectivity-test/v1";
    private static final int MAX_INPUT_CHARS = 250_000;
    private static final Set<String> REQUIRED_RESULT_KEYS = Set.of(
        "summary",
        "root_cause_candidates",
        "action_suggestions",
        "additional_checks"
    );
    private static final String SYSTEM_PROMPT = """
        You are a Kubernetes node and Linux infrastructure RCA assistant.
        Use only the supplied preprocessed evidence. Never claim that a remediation was executed.
        Return JSON only with keys:
        summary, root_cause_candidates, action_suggestions, additional_checks.
        Each root_cause_candidate must contain cause, confidence, and supporting_evidence_ids.
        supporting_evidence_ids must contain one or more exact evidence_id values from evidence_catalog.
        Never create evidence IDs or return free-form supporting evidence.
        Each action_suggestion must contain action_key, action, and reason.
        Prefer read-only verification. Treat destructive or mutating operations as human-reviewed suggestions.
        """;
    private static final String TEST_SYSTEM_PROMPT = """
        You are validating connectivity for Cluster RCA Console.
        Return a short plain text response. Do not include secrets, commands, or remediation steps.
        """;

    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectMapper objectMapper;
    private final RcaConsoleProperties properties;
    private final RcaMetrics metrics;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> circuitOpenUntil = new AtomicReference<>();

    public LlmAnalysisService(
        ObjectProvider<ChatModel> chatModels,
        ObjectMapper objectMapper,
        RcaConsoleProperties properties,
        RcaMetrics metrics
    ) {
        this.chatModels = chatModels;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    public LlmTestResponse testConnection() {
        Instant startedAt = Instant.now();
        String providerName = properties.getLlm().getProvider();
        if (!properties.getLlm().isEnabled()) {
            return new LlmTestResponse(
                "skipped",
                "LLM analyzer is disabled.",
                providerName,
                properties.getLlm().getModel(),
                TEST_PROMPT_VERSION,
                null,
                null,
                ""
            );
        }
        ChatModel chatModel = chatModels.orderedStream().findFirst().orElse(null);
        if (chatModel == null) {
            return new LlmTestResponse(
                "skipped",
                "Spring AI chat model is not configured.",
                providerName,
                properties.getLlm().getModel(),
                TEST_PROMPT_VERSION,
                null,
                null,
                ""
            );
        }
        Instant blockedUntil = circuitOpenUntil.get();
        if (blockedUntil != null && blockedUntil.isAfter(Instant.now())) {
            return new LlmTestResponse(
                "skipped",
                "LLM circuit breaker is open until " + blockedUntil + ".",
                providerName,
                properties.getLlm().getModel(),
                TEST_PROMPT_VERSION,
                null,
                null,
                ""
            );
        }
        long startedNanos = System.nanoTime();
        try {
            Instant requestStartedAt = Instant.now();
            LlmCallResult callResult = callWithTimeout(
                chatModel,
                TEST_SYSTEM_PROMPT,
                "Connectivity check for Cluster RCA Console. Reply with: ok"
            );
            metrics.llmRequest(
                "connectivity_test",
                "completed",
                providerName,
                properties.getLlm().getModel(),
                Duration.between(requestStartedAt, Instant.now())
            );
            recordUsage("connectivity_test", providerName, callResult.usage());
            Duration duration = Duration.between(startedAt, Instant.now());
            metrics.llmAnalysis("test_completed", providerName, duration);
            return new LlmTestResponse(
                "completed",
                "LLM provider returned a response.",
                providerName,
                properties.getLlm().getModel(),
                TEST_PROMPT_VERSION,
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                callResult.content().length(),
                ""
            );
        } catch (Exception exception) {
            Duration duration = Duration.between(startedAt, Instant.now());
            metrics.llmRequest(
                "connectivity_test",
                "failed",
                providerName,
                properties.getLlm().getModel(),
                duration
            );
            metrics.llmAnalysis("test_failed", providerName, duration);
            return new LlmTestResponse(
                "failed",
                "LLM provider test failed.",
                providerName,
                properties.getLlm().getModel(),
                TEST_PROMPT_VERSION,
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                null,
                exception.getClass().getSimpleName() + ": " + safeMessage(exception)
            );
        }
    }

    public Map<String, Object> analyze(Map<String, Object> payload) {
        Instant startedAt = Instant.now();
        String providerName = properties.getLlm().getProvider();
        if (!properties.getLlm().isEnabled()) {
            metrics.llmAnalysis("skipped", providerName, Duration.between(startedAt, Instant.now()));
            return Map.of("status", "skipped", "reason", "llm analyzer disabled");
        }
        ChatModel chatModel = chatModels.orderedStream().findFirst().orElse(null);
        if (chatModel == null) {
            metrics.llmAnalysis("skipped", providerName, Duration.between(startedAt, Instant.now()));
            return Map.of("status", "skipped", "reason", "spring ai chat model not configured");
        }
        Instant blockedUntil = circuitOpenUntil.get();
        if (blockedUntil != null && blockedUntil.isAfter(Instant.now())) {
            metrics.llmAnalysis("circuit_open", providerName, Duration.between(startedAt, Instant.now()));
            return Map.of(
                "status", "skipped",
                "reason", "llm circuit breaker is open",
                "retry_after", blockedUntil.toString()
            );
        }
        long startedNanos = System.nanoTime();
        int maxAttempts = Math.max(1, Math.min(properties.getLlm().getMaxAttempts(), 3));
        Exception lastFailure = null;
        LlmUsage accumulatedUsage = LlmUsage.unavailable();
        try {
            String input = objectMapper.writeValueAsString(payload);
            if (input.length() > MAX_INPUT_CHARS) {
                metrics.llmAnalysis("skipped", providerName, Duration.between(startedAt, Instant.now()));
                return Map.of("status", "skipped", "reason", "preprocessed evidence exceeds llm input limit");
            }
            Map<String, Map<String, Object>> evidenceCatalog = LlmEvidenceCatalog.index(
                payload.get("evidence_catalog")
            );
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                Instant requestStartedAt = Instant.now();
                boolean providerRequestCompleted = false;
                try {
                    LlmCallResult callResult = callWithTimeout(chatModel, SYSTEM_PROMPT, input);
                    Duration requestDuration = Duration.between(requestStartedAt, Instant.now());
                    metrics.llmRequest(
                        "analysis",
                        "completed",
                        providerName,
                        properties.getLlm().getModel(),
                        requestDuration
                    );
                    providerRequestCompleted = true;
                    accumulatedUsage = accumulatedUsage.plus(callResult.usage());
                    Map<String, Object> usage = usageSummary(accumulatedUsage);
                    recordUsage("analysis", providerName, callResult.usage());
                    Map<String, Object> result = parseValidatedResult(callResult.content(), evidenceCatalog);
                    consecutiveFailures.set(0);
                    circuitOpenUntil.set(null);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("status", "completed");
                    response.put("provider", properties.getLlm().getProvider());
                    response.put("model", properties.getLlm().getModel());
                    response.put("prompt_version", PROMPT_VERSION);
                    response.put("attempts", attempt);
                    response.put("latency_ms", Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
                    response.put("usage", usage);
                    response.put("result", result);
                    metrics.llmAnalysis("completed", providerName, Duration.between(startedAt, Instant.now()));
                    return response;
                } catch (Exception exception) {
                    if (!providerRequestCompleted) {
                        metrics.llmRequest(
                            "analysis",
                            "failed",
                            providerName,
                            properties.getLlm().getModel(),
                            Duration.between(requestStartedAt, Instant.now())
                        );
                    }
                    lastFailure = exception;
                }
            }
        } catch (Exception exception) {
            lastFailure = exception;
        }
        int failures = consecutiveFailures.incrementAndGet();
        int threshold = Math.max(1, properties.getLlm().getFailureThreshold());
        if (failures >= threshold) {
            circuitOpenUntil.set(Instant.now().plusSeconds(Math.max(1, properties.getLlm().getCooldownSeconds())));
        }
        Exception failure = lastFailure == null ? new IllegalStateException("analysis failed") : lastFailure;
        metrics.llmAnalysis("failed", providerName, Duration.between(startedAt, Instant.now()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "failed");
        response.put("provider", properties.getLlm().getProvider());
        response.put("model", properties.getLlm().getModel());
        response.put("prompt_version", PROMPT_VERSION);
        response.put("attempts", maxAttempts);
        response.put("latency_ms", Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
        response.put("usage", usageSummary(accumulatedUsage));
        response.put("error", failure.getClass().getSimpleName() + ": " + safeMessage(failure));
        return Map.copyOf(response);
    }

    private LlmCallResult callWithTimeout(ChatModel chatModel, String systemPrompt, String input) throws Exception {
        Future<LlmCallResult> future = executor.submit(() -> {
            ChatClient.ChatClientRequestSpec request = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(systemPrompt)
                .user(input);
            ChatOptions options = chatOptions();
            if (options != null) {
                request = request.options(options);
            }
            ChatResponse response = request.call().chatResponse();
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new IllegalStateException("llm provider returned an empty response");
            }
            String content = response.getResult().getOutput().getText();
            return new LlmCallResult(content == null ? "" : content, usage(response));
        });
        try {
            return future.get(Math.max(1, properties.getLlm().getTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new TimeoutException("llm request timed out");
        }
    }

    private Map<String, Object> parseValidatedResult(
        String content,
        Map<String, Map<String, Object>> evidenceCatalog
    ) throws Exception {
        Map<String, Object> raw = objectMapper.readValue(extractJsonObject(content), new TypeReference<>() {
        });
        validateRawResult(raw, evidenceCatalog.keySet());
        Map<String, Object> normalized = normalizeResult(raw, evidenceCatalog);
        validateNormalizedResult(normalized);
        return normalized;
    }

    private void validateRawResult(Map<String, Object> raw, Set<String> allowedEvidenceIds) {
        List<String> violations = new ArrayList<>();
        for (String key : REQUIRED_RESULT_KEYS) {
            if (!raw.containsKey(key)) {
                violations.add("missing required key: " + key);
            }
        }
        Object summary = raw.get("summary");
        if (summary != null && !(summary instanceof String) && !(summary instanceof Map<?, ?>)) {
            violations.add("summary must be a string or object");
        }
        requireList(raw, "root_cause_candidates", violations);
        requireList(raw, "action_suggestions", violations);
        requireList(raw, "additional_checks", violations);
        validateMapList(raw.get("root_cause_candidates"), "root_cause_candidates", violations);
        validateMapList(raw.get("action_suggestions"), "action_suggestions", violations);
        validateStringList(raw.get("additional_checks"), "additional_checks", violations);
        validateEvidenceReferences(raw.get("root_cause_candidates"), allowedEvidenceIds, violations);
        if (!violations.isEmpty()) {
            throw new LlmResponseValidationException(String.join("; ", violations));
        }
    }

    private void validateNormalizedResult(Map<String, Object> normalized) {
        String summary = String.valueOf(normalized.getOrDefault("summary", "")).trim();
        if (!summary.isBlank()) {
            return;
        }
        if (!mapList(normalized.get("root_cause_candidates"), 1).isEmpty()
            || !mapList(normalized.get("action_suggestions"), 1).isEmpty()
            || !stringList(normalized.get("additional_checks"), 1, 100).isEmpty()) {
            return;
        }
        throw new LlmResponseValidationException("response does not contain usable RCA content");
    }

    private void requireList(Map<String, Object> raw, String key, List<String> violations) {
        Object value = raw.get(key);
        if (value != null && !(value instanceof List<?>)) {
            violations.add(key + " must be a list");
        }
    }

    private void validateMapList(Object value, String key, List<String> violations) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (int index = 0; index < Math.min(list.size(), 5); index++) {
            if (!(list.get(index) instanceof Map<?, ?>)) {
                violations.add(key + "[" + index + "] must be an object");
            }
        }
    }

    private void validateStringList(Object value, String key, List<String> violations) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (int index = 0; index < Math.min(list.size(), 10); index++) {
            Object item = list.get(index);
            if (item instanceof Map<?, ?> || item instanceof List<?>) {
                violations.add(key + "[" + index + "] must be a string");
            }
        }
    }

    private void validateEvidenceReferences(
        Object value,
        Set<String> allowedEvidenceIds,
        List<String> violations
    ) {
        if (!(value instanceof List<?> candidates)) {
            return;
        }
        for (int index = 0; index < Math.min(candidates.size(), 5); index++) {
            if (!(candidates.get(index) instanceof Map<?, ?> candidate)) {
                continue;
            }
            if (candidate.containsKey("supporting_evidence")) {
                violations.add("root_cause_candidates[" + index
                    + "] free-form supporting_evidence is not allowed");
            }
            Object references = candidate.get("supporting_evidence_ids");
            if (!(references instanceof List<?> referenceList)) {
                violations.add("root_cause_candidates[" + index + "].supporting_evidence_ids must be a list");
                continue;
            }
            List<String> evidenceIds = new ArrayList<>();
            for (int referenceIndex = 0; referenceIndex < Math.min(referenceList.size(), 10); referenceIndex++) {
                Object reference = referenceList.get(referenceIndex);
                if (!(reference instanceof String evidenceId) || evidenceId.isBlank()) {
                    violations.add("root_cause_candidates[" + index + "].supporting_evidence_ids["
                        + referenceIndex + "] must be a non-blank string");
                    continue;
                }
                evidenceIds.add(evidenceId.trim());
                if (!allowedEvidenceIds.contains(evidenceId.trim())) {
                    violations.add("root_cause_candidates[" + index + "] references unknown evidence ID: "
                        + evidenceId.trim());
                }
            }
            if (!limitedString(candidate.get("cause"), 1000).isBlank() && evidenceIds.isEmpty()) {
                violations.add("root_cause_candidates[" + index + "] must reference supporting evidence");
            }
        }
    }

    private Map<String, Object> normalizeResult(
        Map<String, Object> raw,
        Map<String, Map<String, Object>> evidenceCatalog
    ) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("summary", summary(raw.get("summary")));
        normalized.put(
            "root_cause_candidates",
            mapList(raw.get("root_cause_candidates"), 5).stream()
                .map(item -> normalizeCandidate(item, evidenceCatalog))
                .filter(item -> !String.valueOf(item.get("cause")).isBlank())
                .toList()
        );
        normalized.put(
            "action_suggestions",
            mapList(raw.get("action_suggestions"), 5).stream().map(item -> Map.of(
                "action_key", actionKey(item.get("action_key")),
                "action", limitedString(item.get("action"), 1000),
                "reason", limitedString(item.get("reason"), 1000)
            )).filter(item -> !String.valueOf(item.get("action")).isBlank()
                && !String.valueOf(item.get("reason")).isBlank()).toList()
        );
        normalized.put("additional_checks", stringList(raw.get("additional_checks"), 10, 1000));
        return normalized;
    }

    private Map<String, Object> normalizeCandidate(
        Map<String, Object> item,
        Map<String, Map<String, Object>> evidenceCatalog
    ) {
        List<String> evidenceIds = stringList(item.get("supporting_evidence_ids"), 10, 64).stream()
            .filter(evidenceCatalog::containsKey)
            .distinct()
            .toList();
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("cause", limitedString(item.get("cause"), 1000));
        candidate.put("confidence", confidence(item.get("confidence")));
        candidate.put("supporting_evidence_ids", evidenceIds);
        candidate.put("supporting_evidence", LlmEvidenceCatalog.descriptions(evidenceIds, evidenceCatalog));
        candidate.put("evidence_paths", LlmEvidenceCatalog.evidencePaths(evidenceIds, evidenceCatalog));
        return Map.copyOf(candidate);
    }

    private String summary(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            appendSummaryPart(parts, "Cause", map.get("most_likely_cause"), 700);
            appendSummaryPart(parts, "Confidence", map.get("confidence"), 80);
            appendSummaryPart(parts, "Reasoning", map.get("reasoning"), 1000);
            String joined = String.join(" | ", parts);
            return joined.isBlank() ? limitedString(value, 2000) : joined;
        }
        return limitedString(value, 2000);
    }

    private void appendSummaryPart(List<String> parts, String label, Object value, int maxLength) {
        String text = limitedString(value, maxLength);
        if (!text.isBlank()) {
            parts.add(label + ": " + text);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value, int limit) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item)
            .limit(limit)
            .toList();
    }

    private List<String> stringList(Object value, int limit, int maxLength) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(item -> limitedString(item, maxLength))
            .filter(item -> !item.isBlank())
            .limit(limit)
            .toList();
    }

    private String confidence(Object value) {
        String confidence = limitedString(value, 16).toLowerCase(Locale.ROOT);
        return Set.of("low", "medium", "high").contains(confidence) ? confidence : "low";
    }

    private String actionKey(Object value) {
        String key = limitedString(value, 128).toLowerCase(Locale.ROOT);
        return key.matches("[a-z0-9][a-z0-9_.-]{0,127}") ? key : "manual_investigation";
    }

    private String limitedString(Object value, int maxLength) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String extractJsonObject(String value) {
        String content = stripFence(value);
        int start = content.indexOf('{');
        if (start < 0) {
            return content;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < content.length(); index++) {
            char current = content.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, index + 1);
                }
            }
        }
        return content;
    }

    private String stripFence(String value) {
        String content = value == null ? "" : value.trim();
        if (content.startsWith("```")) {
            int firstLine = content.indexOf('\n');
            int lastFence = content.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                return content.substring(firstLine + 1, lastFence).trim();
            }
        }
        return content;
    }

    private ChatOptions chatOptions() {
        String provider = properties.getLlm().getProvider().trim().toLowerCase(Locale.ROOT);
        String model = properties.getLlm().getModel().trim();
        int maxTokens = Math.max(128, properties.getLlm().getMaxOutputTokens());
        return switch (provider) {
            case "openai", "openai-sdk", "openai_compatible", "self_hosted" -> {
                OpenAiSdkChatOptions.Builder builder = OpenAiSdkChatOptions.builder()
                    .maxCompletionTokens(maxTokens)
                    .internalToolExecutionEnabled(false);
                if (!model.isBlank()) {
                    builder.model(model);
                }
                yield builder.build();
            }
            case "anthropic", "claude" -> {
                AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                    .maxTokens(maxTokens)
                    .internalToolExecutionEnabled(false);
                if (!model.isBlank()) {
                    builder.model(model);
                }
                yield builder.build();
            }
            case "gemini", "google", "google-genai" -> {
                GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder()
                    .maxOutputTokens(maxTokens)
                    .internalToolExecutionEnabled(false);
                if (!model.isBlank()) {
                    builder.model(model);
                }
                yield builder.build();
            }
            case "ollama" -> {
                OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                    .numPredict(maxTokens)
                    .internalToolExecutionEnabled(false);
                if (!model.isBlank()) {
                    builder.model(model);
                }
                yield builder.build();
            }
            default -> null;
        };
    }

    private LlmUsage usage(ChatResponse response) {
        Usage providerUsage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (providerUsage == null) {
            return LlmUsage.unavailable();
        }
        Integer promptTokens = providerUsage.getPromptTokens();
        Integer completionTokens = providerUsage.getCompletionTokens();
        Integer totalTokens = providerUsage.getTotalTokens();
        boolean available = promptTokens != null || completionTokens != null || totalTokens != null;
        int input = nonNegative(promptTokens);
        int output = nonNegative(completionTokens);
        int total = totalTokens == null ? input + output : nonNegative(totalTokens);
        return new LlmUsage(input, output, total, available);
    }

    private Map<String, Object> usageSummary(LlmUsage usage) {
        double inputPrice = Math.max(0, properties.getLlm().getInputCostPerMillionTokens());
        double outputPrice = Math.max(0, properties.getLlm().getOutputCostPerMillionTokens());
        boolean costEstimationEnabled = inputPrice > 0 || outputPrice > 0;
        BigDecimal estimatedCost = estimatedCost(usage, inputPrice, outputPrice);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("usage_available", usage.available());
        summary.put("input_tokens", usage.inputTokens());
        summary.put("output_tokens", usage.outputTokens());
        summary.put("total_tokens", usage.totalTokens());
        summary.put("cost_estimation_enabled", costEstimationEnabled);
        summary.put("estimated_cost_usd", estimatedCost);
        summary.put("pricing_unit", "usd_per_million_tokens");
        return Map.copyOf(summary);
    }

    private void recordUsage(String operation, String provider, LlmUsage usage) {
        Map<String, Object> summary = usageSummary(usage);
        metrics.llmUsage(
            operation,
            provider,
            properties.getLlm().getModel(),
            usage.inputTokens(),
            usage.outputTokens(),
            usage.totalTokens(),
            ((BigDecimal) summary.get("estimated_cost_usd")).doubleValue(),
            usage.available()
        );
    }

    private BigDecimal estimatedCost(LlmUsage usage, double inputPrice, double outputPrice) {
        if (!usage.available() || (inputPrice <= 0 && outputPrice <= 0)) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal inputCost = BigDecimal.valueOf(usage.inputTokens())
            .multiply(BigDecimal.valueOf(inputPrice));
        BigDecimal outputCost = BigDecimal.valueOf(usage.outputTokens())
            .multiply(BigDecimal.valueOf(outputPrice));
        return inputCost.add(outputCost)
            .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String safeMessage(Exception exception) {
        String message = null;
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        if (message == null || message.isBlank()) {
            return "analysis failed";
        }
        String redacted = SensitiveDataRedactor.redactText(message);
        return redacted.length() > 300 ? redacted.substring(0, 300) : redacted;
    }

    @PreDestroy
    public void shutdown() {
        executor.close();
    }

    static class LlmResponseValidationException extends RuntimeException {
        LlmResponseValidationException(String message) {
            super(message);
        }
    }

    private record LlmCallResult(String content, LlmUsage usage) {
    }

    private record LlmUsage(int inputTokens, int outputTokens, int totalTokens, boolean available) {
        private static LlmUsage unavailable() {
            return new LlmUsage(0, 0, 0, false);
        }

        private LlmUsage plus(LlmUsage other) {
            if (other == null || !other.available()) {
                return this;
            }
            return new LlmUsage(
                saturatedAdd(inputTokens, other.inputTokens()),
                saturatedAdd(outputTokens, other.outputTokens()),
                saturatedAdd(totalTokens, other.totalTokens()),
                true
            );
        }

        private static int saturatedAdd(int left, int right) {
            return (int) Math.min(Integer.MAX_VALUE, (long) left + right);
        }
    }
}
