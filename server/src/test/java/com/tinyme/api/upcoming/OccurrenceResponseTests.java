package com.tinyme.api.upcoming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OccurrenceResponseTests {
    @Test
    void saysWhereEachPlanCameFromAndLinksAJobTaskToItsPosting() {
        assertThat(OccurrenceResponse.origin("chat", "gmail:abc")).isEqualTo("gmail");
        assertThat(OccurrenceResponse.origin("chat", "linkedin:4012345678")).isEqualTo("linkedin");
        assertThat(OccurrenceResponse.origin("chat", "job:0199a000-0000-7000-8000-000000000001")).isEqualTo("linkedin");
        assertThat(OccurrenceResponse.origin("google", "event-1")).isEqualTo("google_calendar");
        assertThat(OccurrenceResponse.origin("chat", null)).as("made in TinyMe").isNull();
        assertThat(OccurrenceResponse.origin("people", "birthday:aiko")).isNull();

        assertThat(OccurrenceResponse.originUrl("chat", "linkedin:4012345678"))
                .isEqualTo("https://www.linkedin.com/jobs/view/4012345678/");
        assertThat(OccurrenceResponse.originUrl("chat", "linkedin:../evil")).isNull();
        assertThat(OccurrenceResponse.originUrl("chat", "gmail:abc")).isNull();
    }
}
