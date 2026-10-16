package com.tinyme.integrations.google.api;

import com.tinyme.integrations.google.service.JobLeadRepository;
import com.tinyme.integrations.google.service.MailScanScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobLeadsControllerTests {
    private final JobLeadRepository jobs = mock(JobLeadRepository.class);
    private final MailScanScheduler scans = mock(MailScanScheduler.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC);
    private final JobLeadsController controller = new JobLeadsController(jobs, scans, clock);

    private static HttpStatus status(Throwable error) {
        return HttpStatus.valueOf(((ResponseStatusException) error).getStatusCode().value());
    }

    @Test
    void theFirstPageAsksForAScanAndCarriesTheNewCountLaterPagesDont() {
        String next = "MjAyNi0xMC0xNlQwMzowMDowMFp8MDE5YTAwMDAtMDAwMC03MDAwLTgwMDAtMDAwMDAwMDAwMDAx";
        var after = JobLeadRepository.Cursor.decode(next);
        when(jobs.page(null, null, 20)).thenReturn(new JobLeadRepository.Page(List.of(), next));
        when(jobs.page("applied", after, 50)).thenReturn(new JobLeadRepository.Page(List.of(), null));
        when(jobs.countNew()).thenReturn(7);

        assertThat(controller.list(null, null, 20).newCount()).isEqualTo(7);
        assertThat(controller.list("applied", next, 500).nextCursor()).isNull();   // limit capped at 50
        verify(scans).requestNow();
    }

    @Test
    void refusesAnUnknownStatusAndReportsAMissingJob() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> controller.update(id, new JobLeadsController.StatusRequest("hired")))
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.list("hired", null, 20))
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.list(null, "not-a-cursor", 20))
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.BAD_REQUEST));
        when(jobs.setStatus(id, "applied", clock.instant())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.update(id, new JobLeadsController.StatusRequest("applied")))
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.NOT_FOUND));
        when(jobs.delete(id, clock.instant())).thenReturn(false);
        assertThatThrownBy(() -> controller.delete(id))
                .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.NOT_FOUND));
        verify(jobs, never()).setStatus(id, "hired", clock.instant());
    }
}
