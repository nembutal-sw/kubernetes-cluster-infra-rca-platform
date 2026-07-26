package io.clusterinfra.rca.webconsole.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkerLeaseConfigurationValidatorTests {
    @Test
    void defaultConfigurationHasSafeLeaseWindows() {
        RcaConsoleProperties properties = new RcaConsoleProperties();

        assertThatCode(() -> validator(properties).afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    @Test
    void notificationLeaseMustExceedTimeoutAndSafetyMargin() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getNotification().setEnabled(true);
        properties.getNotification().setTimeoutSeconds(30);
        properties.getNotification().setLeaseSeconds(45);

        assertThatThrownBy(() -> validator(properties).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RCA_NOTIFICATION_LEASE_SECONDS")
            .hasMessageContaining("minimum 46");
    }

    @Test
    void pipelineLeaseMustCoverEveryLlmAttemptAndDatabaseMargin() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setTimeoutSeconds(30);
        properties.getLlm().setMaxAttempts(3);
        properties.getPipeline().setLeaseSeconds(120);

        assertThatThrownBy(() -> validator(properties).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RCA_PIPELINE_LEASE_SECONDS")
            .hasMessageContaining("minimum 121");
    }

    @Test
    void leaseJustAboveCalculatedPipelineWindowIsAccepted() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setTimeoutSeconds(30);
        properties.getLlm().setMaxAttempts(3);
        properties.getPipeline().setLeaseSeconds(121);

        assertThatCode(() -> validator(properties).afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    private WorkerLeaseConfigurationValidator validator(RcaConsoleProperties properties) {
        return new WorkerLeaseConfigurationValidator(properties);
    }
}
