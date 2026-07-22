package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class HttpKubernetesApiTransportTests {
    @Test
    void readsOnlyUpToTheConfiguredResponseBoundary() throws Exception {
        byte[] body = new byte[] {1, 2, 3, 4};

        assertThat(HttpKubernetesApiTransport.readBounded(
            new ByteArrayInputStream(body),
            body.length,
            "TokenReview"
        )).containsExactly(body);
    }

    @Test
    void rejectsAResponseBeforeBufferingBeyondTheBoundary() {
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5, 6});

        assertThatThrownBy(() -> HttpKubernetesApiTransport.readBounded(body, 4, "TokenReview"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("response exceeded the size limit");
        assertThat(body.available()).isEqualTo(1);
    }
}
