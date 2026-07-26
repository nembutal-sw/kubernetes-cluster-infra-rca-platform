package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentIdentity;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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
    private static final Pattern SHA256_DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");

    private final KubernetesApiTransport transport;

    public KubernetesTokenReviewService(KubernetesApiTransport transport) {
        this.transport = transport;
    }

    public AgentEnrollmentIdentity verify(
        AgentEnrollmentConfiguration configuration,
        String token,
        String requestedNodeName
    ) {
        if (!configuration.workloadIdentityReady()) {
            throw unauthorized("Kubernetes workload identity binding is incomplete");
        }
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
        if (!configuration.expectedServiceAccountUid().equals(serviceAccountUid)) {
            throw unauthorized("Kubernetes enrollment ServiceAccount UID did not match");
        }
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
        JsonNode pod = transport.pod(configuration, configuration.namespace(), podName);
        String imageDigest = verifyPod(configuration, pod, podName, podUid, requestedNodeName);
        return new AgentEnrollmentIdentity(
            "kubernetes_token_review",
            subject,
            serviceAccountUid,
            configuration.namespace(),
            configuration.serviceAccount(),
            podName,
            podUid,
            configuration.expectedDaemonSetName(),
            configuration.expectedDaemonSetUid(),
            imageDigest,
            configuration.profileVersion()
        );
    }

    private String verifyPod(
        AgentEnrollmentConfiguration configuration,
        JsonNode pod,
        String expectedPodName,
        String expectedPodUid,
        String expectedNodeName
    ) {
        JsonNode metadata = pod.path("metadata");
        JsonNode spec = pod.path("spec");
        JsonNode status = pod.path("status");
        if (!expectedPodName.equals(metadata.path("name").asText())
            || !configuration.namespace().equals(metadata.path("namespace").asText())
            || !expectedPodUid.equals(metadata.path("uid").asText())
            || (metadata.has("deletionTimestamp") && !metadata.path("deletionTimestamp").isNull())
            || !configuration.serviceAccount().equals(spec.path("serviceAccountName").asText())
            || !expectedNodeName.equals(spec.path("nodeName").asText())
            || !"Running".equals(status.path("phase").asText())) {
            throw unauthorized("Kubernetes Pod binding did not match the agent registration");
        }
        verifyLabels(configuration.requiredPodLabels(), metadata.path("labels"));
        verifyDaemonSetOwner(configuration, metadata.path("ownerReferences"));
        return verifyImageDigest(configuration.allowedImageDigest(), status.path("containerStatuses"));
    }

    private void verifyLabels(Map<String, String> expected, JsonNode actual) {
        for (Map.Entry<String, String> label : expected.entrySet()) {
            if (!label.getValue().equals(actual.path(label.getKey()).asText(null))) {
                throw unauthorized("Kubernetes Pod required labels did not match");
            }
        }
    }

    private void verifyDaemonSetOwner(
        AgentEnrollmentConfiguration configuration,
        JsonNode ownerReferences
    ) {
        if (ownerReferences.isArray()) {
            for (JsonNode owner : ownerReferences) {
                if ("apps/v1".equals(owner.path("apiVersion").asText())
                    && "DaemonSet".equals(owner.path("kind").asText())
                    && owner.path("controller").asBoolean(false)
                    && configuration.expectedDaemonSetName().equals(owner.path("name").asText())
                    && configuration.expectedDaemonSetUid().equals(owner.path("uid").asText())) {
                    return;
                }
            }
        }
        throw unauthorized("Kubernetes Pod DaemonSet owner identity did not match");
    }

    private String verifyImageDigest(String expectedDigest, JsonNode containerStatuses) {
        if (containerStatuses.isArray()) {
            for (JsonNode container : containerStatuses) {
                if (!"agent".equals(container.path("name").asText())
                    || !container.path("state").path("running").isObject()) {
                    continue;
                }
                Matcher matcher = SHA256_DIGEST.matcher(container.path("imageID").asText(""));
                if (matcher.find() && expectedDigest.equals(matcher.group())) {
                    return matcher.group();
                }
            }
        }
        throw unauthorized("Kubernetes Agent container image digest did not match");
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
