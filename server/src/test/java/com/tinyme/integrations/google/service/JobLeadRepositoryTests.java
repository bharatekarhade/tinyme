package com.tinyme.integrations.google.service;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class JobLeadRepositoryTests {
    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");

    @Autowired JobLeadRepository jobs;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM job_leads");
    }

    private static MailScanRepository.Candidate job(String title, String company, String url) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", title);
        payload.put("company", company);
        payload.put("location", "Tokyo, Japan");
        if (url != null) payload.put("url", url);
        return new MailScanRepository.Candidate("job", payload, 0.9, title);
    }

    @Test
    void theSamePostingInTomorrowsAlertIsNotAddedTwiceEvenAfterItWasDeleted() {
        assertThat(jobs.save("mail-1", job("Backend Engineer", "Acme KK", "https://www.linkedin.com/jobs/view/4012345678/"))).isTrue();
        assertThat(jobs.save("mail-2", job("Backend Engineer (Java)", "Acme", "https://www.linkedin.com/comm/jobs/view/4012345678/?x=1")))
                .as("same LinkedIn job id").isFalse();
        assertThat(jobs.save("mail-3", job("iOS Engineer", "Example Inc.", null))).isTrue();
        assertThat(jobs.save("mail-4", job("  iOS   engineer ", "example inc.", null)))
                .as("same company and title without a link").isFalse();

        UUID ios = jobs.page(null, null, 10).items().stream()
                .filter(item -> item.title().equals("iOS Engineer")).findFirst().orElseThrow().id();
        assertThat(jobs.delete(ios, NOW)).isTrue();
        assertThat(jobs.save("mail-5", job("iOS Engineer", "Example Inc.", null))).as("deleted stays gone").isFalse();
        assertThat(jobs.page(null, null, 10).items()).extracting(JobLeadRepository.JobLead::title)
                .containsExactly("Backend Engineer");
    }

    @Test
    void pagesWalkTheListNewestFirstWithoutRepeatsAndFilterByStatus() {
        for (int i = 0; i < 25; i++) {
            jobs.save("mail-" + i, job("Engineer " + i, "Company " + i, "https://www.linkedin.com/jobs/view/40000000" + (10 + i) + "/"));
            jdbc.update("UPDATE job_leads SET found_at = ? WHERE title = ?",
                    java.sql.Timestamp.from(NOW.minusSeconds(60L * (25 - i))), "Engineer " + i);
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var page = jobs.page(null, JobLeadRepository.Cursor.decode(cursor), 10);
            page.items().forEach(item -> seen.add(item.title()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(25).doesNotHaveDuplicates().startsWith("Engineer 24", "Engineer 23").endsWith("Engineer 0");

        UUID applied = jobs.page(null, null, 1).items().getFirst().id();
        assertThat(jobs.setStatus(applied, "applied", NOW)).hasValueSatisfying(job -> {
            assertThat(job.status()).isEqualTo("applied");
            assertThat(job.statusChangedAt()).isEqualTo(NOW);
        });
        assertThat(jobs.page("applied", null, 10).items()).extracting(JobLeadRepository.JobLead::id).containsExactly(applied);
        assertThat(jobs.countNew()).isEqualTo(24);
        assertThatThrownBy(() -> JobLeadRepository.Cursor.decode("not-a-cursor")).isInstanceOf(IllegalArgumentException.class);
    }
}
