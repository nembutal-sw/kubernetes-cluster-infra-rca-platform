package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.catalog.OperationalCatalogService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CollectorSelectionService {
    private final OperationalCatalogService catalogService;

    public CollectorSelectionService() {
        this(OperationalCatalogService.defaultService());
    }

    @Autowired
    public CollectorSelectionService(OperationalCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public List<String> collectorsFor(String alertName) {
        return catalogService.collectorsForAlert(alertName);
    }
}
