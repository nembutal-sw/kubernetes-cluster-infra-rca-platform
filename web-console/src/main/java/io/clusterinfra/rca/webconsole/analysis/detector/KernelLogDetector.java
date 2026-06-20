package io.clusterinfra.rca.webconsole.analysis.detector;

import io.clusterinfra.rca.webconsole.analysis.AnalysisContext;
import io.clusterinfra.rca.webconsole.analysis.DetectorSupport;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.analysis.SignalDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class KernelLogDetector implements SignalDetector {
    private static final Pattern KERNEL_IO = Pattern.compile(
        "(i/o error|buffer i/o|blk_update_request|nvme.*error|ext4.*error|xfs.*error|read-only file system)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OOM = Pattern.compile(
        "(out of memory|oom-kill|killed process .* memory)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BLOCKED_TASK = Pattern.compile(
        "(blocked for more than|hung task|soft lockup|hard lockup)",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public String id() {
        return "kernel-log";
    }

    @Override
    public List<Signal> detect(AnalysisContext context) {
        List<Signal> signals = new ArrayList<>();
        String searchable = context.searchable();
        if (KERNEL_IO.matcher(searchable).find()) {
            signals.add(DetectorSupport.matchedSignal(
                searchable.toLowerCase(Locale.ROOT).contains("read-only file system")
                    ? "root_filesystem_read_only" : "kernel_io_error",
                "kernel",
                "critical",
                "kernel log match",
                List.of("kernel logs"),
                "Kernel logs contain storage or filesystem I/O errors.",
                "Inspect dmesg, filesystem state, block devices, mounts, and storage hardware.",
                "kernel", "disk"
            ));
        }
        if (OOM.matcher(searchable).find()) {
            signals.add(DetectorSupport.matchedSignal(
                "kernel_oom_detected",
                "memory",
                "critical",
                "kernel OOM log match",
                List.of("kernel logs"),
                "The kernel recorded an out-of-memory kill.",
                "Identify the killed process and correlate node and cgroup memory pressure.",
                "kernel", "memory"
            ));
        }
        if (BLOCKED_TASK.matcher(searchable).find()) {
            signals.add(DetectorSupport.matchedSignal(
                "blocked_task_detected",
                "kernel",
                "critical",
                "blocked task log match",
                List.of("kernel logs"),
                "Kernel blocked-task or lockup messages indicate stalled execution.",
                "Inspect blocked stacks, storage latency, locks, and kernel health before disruptive action.",
                "kernel"
            ));
        }
        return signals;
    }
}
