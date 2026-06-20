package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJob;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ReportRepository {
    private final JdbcRcaStore store;

    public ReportRepository(JdbcRcaStore store) {
        this.store = store;
    }

    public RcaJob save(RcaReport report, RcaJob job) {
        return store.saveReportAndJob(report, job);
    }

    public RcaReport saveReport(RcaReport report) {
        return store.saveReport(report);
    }

    public List<RcaJob> listJobs() {
        return store.listJobs();
    }

    public Optional<RcaJob> findJob(String jobId) {
        return store.getJob(jobId);
    }

    public List<RcaReport> listReports() {
        return store.listReports();
    }

    public Optional<RcaReport> findReport(String reportId) {
        return store.getReport(reportId);
    }
}
