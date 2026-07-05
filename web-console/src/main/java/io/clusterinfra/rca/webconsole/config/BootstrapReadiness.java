package io.clusterinfra.rca.webconsole.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class BootstrapReadiness {
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public boolean isCompleted() {
        return completed.get();
    }

    public void markCompleted() {
        completed.set(true);
    }
}
