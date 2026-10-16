package com.tinyme.integrations.google.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.integrations.google.service.JobLeadRepository;
import com.tinyme.integrations.google.service.MailScanScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Job postings from job-alert emails, newest first, a page at a time. The owner sets each one's status or deletes it. */
@RestController
@RequestMapping("/integrations/google/jobs")
public class JobLeadsController {
    private static final int MAX_PAGE = 50;
    private final JobLeadRepository jobs;
    private final MailScanScheduler scans;
    private final Clock clock;

    public JobLeadsController(JobLeadRepository jobs, MailScanScheduler scans, Clock clock) {
        this.jobs = jobs;
        this.scans = scans;
        this.clock = clock;
    }

    @GetMapping
    public PageResponse list(@RequestParam(required = false) String status,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(defaultValue = "20") int limit) {
        if (cursor == null) scans.requestNow();
        try {
            var page = jobs.page(status == null || status.isBlank() ? null : known(status),
                    JobLeadRepository.Cursor.decode(cursor),
                    Math.max(1, Math.min(MAX_PAGE, limit)));
            return new PageResponse(page.items().stream().map(JobResponse::from).toList(), page.nextCursor(),
                    jobs.countNew());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @PatchMapping("/{id}")
    public JobResponse update(@PathVariable UUID id, @RequestBody StatusRequest request) {
        String status;
        try {
            status = known(request == null ? null : request.status());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        return jobs.setStatus(id, status, clock.instant()).map(JobResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        if (!jobs.delete(id, clock.instant())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    }

    private static String known(String status) {
        if (status == null || !JobLeadRepository.STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + String.join(", ", JobLeadRepository.STATUSES));
        }
        return status;
    }

    public record StatusRequest(String status) { }

    /** {@code new_count} is how many jobs are still new, for the G Scan badge. */
    public record PageResponse(List<JobResponse> items, @JsonProperty("next_cursor") String nextCursor,
                               @JsonProperty("new_count") int newCount) { }

    public record JobResponse(UUID id, String title, String company, String location, String url, String status,
                              @JsonProperty("found_at") Instant foundAt,
                              @JsonProperty("status_changed_at") Instant statusChangedAt) {
        static JobResponse from(JobLeadRepository.JobLead job) {
            return new JobResponse(job.id(), job.title(), job.company(), job.location(), job.url(), job.status(),
                    job.foundAt(), job.statusChangedAt());
        }
    }
}
