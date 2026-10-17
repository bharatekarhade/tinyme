package com.tinyme.integrations.google.service;

import com.tinyme.domain.plans.model.PlanAddCommand;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.settings.UserZone;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Turns a job from a job alert into a task for today, once. */
@Service
public class JobTaskService {
    /** The longest plan title the database takes. */
    private static final int MAX_TITLE = 120;

    private final JobLeadRepository jobs;
    private final PlanService plans;
    private final UserZone zone;
    private final Clock clock;

    public JobTaskService(JobLeadRepository jobs, PlanService plans, UserZone zone, Clock clock) {
        this.jobs = jobs;
        this.plans = plans;
        this.zone = zone;
        this.clock = clock;
    }

    /** The job's task: made now, or the one an earlier tap made. Empty when there is no such job. */
    @Transactional
    public Optional<JobTask> addTask(UUID id) {
        var job = jobs.lockForTask(id).orElse(null);
        if (job == null) return Optional.empty();
        if (job.planId() != null) return Optional.of(new JobTask(id, job.planId(), false));
        var created = plans.add(new PlanAddCommand(PlanKind.TASK, title(job.title(), job.company()),
                LocalDate.now(clock.withZone(zone.current())), null, null, null, null, List.of(),
                job.location(), null, null, null), zone.current(), clock.instant(), externalId(id, job.dedupeKey()));
        jobs.linkTask(id, created.id(), clock.instant());
        return Optional.of(new JobTask(id, created.id(), true));
    }

    /** "Apply: iOS Engineer at Mercari", shortened to fit. */
    static String title(String jobTitle, String company) {
        String full = "Apply: " + jobTitle.strip() + " at " + company.strip();
        return full.length() <= MAX_TITLE ? full : full.substring(0, MAX_TITLE - 1).strip() + "…";
    }

    /** linkedin:&lt;job id&gt; lets Upcoming link the task to the posting; a job without a link keeps its own id. */
    static String externalId(UUID id, String dedupeKey) {
        return dedupeKey.startsWith("linkedin:") ? dedupeKey : "job:" + id;
    }

    public record JobTask(UUID jobId, UUID planId, boolean created) { }
}
