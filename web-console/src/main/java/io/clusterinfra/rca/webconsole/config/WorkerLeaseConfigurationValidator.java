package io.clusterinfra.rca.webconsole.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class WorkerLeaseConfigurationValidator implements InitializingBean {
    static final int NOTIFICATION_SAFETY_MARGIN_SECONDS = 15;
    static final int PIPELINE_DB_SAFETY_MARGIN_SECONDS = 30;

    private final RcaConsoleProperties properties;

    public WorkerLeaseConfigurationValidator(RcaConsoleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> violations = new ArrayList<>();
        validateNotification(violations);
        validatePipeline(violations);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                "Unsafe worker lease configuration:\n - " + String.join("\n - ", violations)
            );
        }
    }

    private void validateNotification(List<String> violations) {
        RcaConsoleProperties.Notification notification = properties.getNotification();
        if (!notification.isEnabled()) {
            return;
        }
        int timeoutSeconds = notification.getTimeoutSeconds();
        int leaseSeconds = notification.getLeaseSeconds();
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            violations.add("RCA_NOTIFICATION_TIMEOUT_SECONDS must be between 1 and 30");
            return;
        }
        long minimumExclusive = (long) timeoutSeconds + NOTIFICATION_SAFETY_MARGIN_SECONDS;
        if (leaseSeconds <= minimumExclusive) {
            violations.add(
                "RCA_NOTIFICATION_LEASE_SECONDS must be greater than notification timeout plus "
                    + NOTIFICATION_SAFETY_MARGIN_SECONDS
                    + " seconds (minimum "
                    + (minimumExclusive + 1)
                    + ")"
            );
        }
    }

    private void validatePipeline(List<String> violations) {
        RcaConsoleProperties.Pipeline pipeline = properties.getPipeline();
        if (!pipeline.isEnabled()) {
            return;
        }
        long maximumAnalysisSeconds = PIPELINE_DB_SAFETY_MARGIN_SECONDS;
        if (properties.getLlm().isEnabled()) {
            int timeoutSeconds = properties.getLlm().getTimeoutSeconds();
            int maxAttempts = properties.getLlm().getMaxAttempts();
            if (timeoutSeconds < 1 || timeoutSeconds > 900) {
                violations.add("RCA_LLM_TIMEOUT_SECONDS must be between 1 and 900");
                return;
            }
            if (maxAttempts < 1 || maxAttempts > 3) {
                violations.add("RCA_LLM_MAX_ATTEMPTS must be between 1 and 3");
                return;
            }
            maximumAnalysisSeconds += (long) timeoutSeconds * maxAttempts;
        }
        if (pipeline.getLeaseSeconds() <= maximumAnalysisSeconds) {
            violations.add(
                "RCA_PIPELINE_LEASE_SECONDS must be greater than the maximum LLM attempt window "
                    + "plus "
                    + PIPELINE_DB_SAFETY_MARGIN_SECONDS
                    + " seconds for database processing (minimum "
                    + (maximumAnalysisSeconds + 1)
                    + ")"
            );
        }
    }
}
