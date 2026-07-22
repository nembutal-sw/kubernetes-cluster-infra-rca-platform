package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentIdentity;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KubernetesTokenReviewService {
    private static final String POD_NAME_EXTRA = "authentication.kubernetes.io/pod-name";
    private static final String POD_UID_EXTRA = "authentication.kubernetes.io/pod-uid";
    private static final int MAX_TOKEN_LENGTH = 32768;
    private static final Pattern POD_NAME = Pattern.compile(
        "[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?"
    );

    private final KubernetesApiTransport transport;

    public KubernetesTokenReviewService(KubernetesApiTransport transport) {
        this.transport = transport;
    }

    public AgentEnrollmentIdentity verify(
        AgentEnrollmentConfiguration configuration,
        String token,
        String requestedNodeName
    ) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw unauthorized("Kubernetes enrollment token is missing or invalid");
        }
        JsonNode review = transport.reviewToken(configuration, token);
        JsonNode status = review.path("status");
        if (!status.path("authenticated").asBoolean(false)) {
            throw unauthorized("Kubernetes enrollment token was not authenticated");
        }
        if (!textSet(status.path("audiences")).contains(configuration.audience())) {
            throw unauthorized("Kubernetes enrollment audience did not match");
        }

        JsonNode user = status.path("user");
        String expectedSubject = "system:serviceaccount:" + configuration.namespace()
            + ":" + configuration.serviceAccount();
        String subject = requiredText(user, "username", "TokenReview subject is missing");
        if (!expectedSubject.equals(subject)) {
            throw unauthorized("Kubernetes enrollment ServiceAccount did not match");
        }
        String serviceAccountUid = bounded(
            requiredText(user, "uid", "TokenReview ServiceAccount UID is missing"),
            255,
            "TokenReview ServiceAccount UID is invalid"
        );
        Set<String> groups = textSet(user.path("groups"));
        if (!groups.contains("system:authenticated")
            || !groups.contains("system:serviceaccounts")
            || !groups.contains("system:serviceaccounts:" + configuration.namespace())) {
            throw unauthorized("Kubernetes enrollment groups did not match");
        }

        JsonNode extra = user.path("extra");
        String podName = firstText(extra.path(POD_NAME_EXTRA), "TokenReview Pod name is missing");
        String podUid = firstText(extra.path(POD_UID_EXTRA), "TokenReview Pod UID is missing");
        if (podName.length() > 253 || !POD_NAME.matcher(podName).matches()) {
            throw unauthorized("TokenReview Pod name is invalid");
        }
        podUid = bounded(podUid, 255, "TokenReview Pod UID is invalid");
        JsonNode pod = transport.pod(configuration, token, configuration.namespace(), podName);
        verifyPod(configuration, pod, podName, podUid, requestedNodeName);
        return new AgentEnrollmentIdentity(
            "kubernetes_token_review",
            subject,
            serviceAccountUid,
            configuration.namespace(),
            configuration.serviceAccount(),
            podName,
            podUid
        );
    }

    private void verifyPod(
        AgentEnrollmentConfiguration configuration,
        JsonNode pod,
        String expectedPodName,
        String expectedPodUid,
        String expectedNodeName
    ) {
        JsonNode metadata = pod.path("metadata");
        JsonNode spec = pod.path("spec");
        if (!expectedPodName.equals(metadata.path("name").asText())
            || !configuration.namespace().equals(metadata.path("namespace").asText())
            || !expectedPodUid.equals(metadata.path("uid").asText())
            || (metadata.has("deletionTimestamp") && !metadata.path("deletionTimestamp").isNull())
            || !configuration.serviceAccount().equals(spec.path("serviceAccountName").asText())
            || !expectedNodeName.equals(spec.path("nodeName").asText())) {
            throw unauthorized("Kubernetes Pod binding did not match the agent registration");
        }
    }

    private String requiredText(JsonNode object, String field, String message) {
        String value = object.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw unauthorized(message);
        }
        return value;
    }

    private String firstText(JsonNode values, String message) {
        if (!values.isArray() || values.isEmpty()) {
            throw unauthorized(message);
        }
        String value = values.get(0).asText("").trim();
        if (value.isEmpty()) {
            throw unauthorized(message);
        }
        return value;
    }

    private Set<String> textSet(JsonNode values) {
        Set<String> result = new HashSet<>();
        if (values.isArray()) {
            values.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    result.add(value.asText());
                }
            });
        }
        return result;
    }

    private String bounded(String value, int maximum, String message) {
        if (value.length() > maximum) {
            throw unauthorized(message);
        }
        return value;
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}
