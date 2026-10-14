package com.tinyme.domain.health;

import com.tinyme.domain.health.model.HealthGroup;
import com.tinyme.domain.health.service.HealthKinds;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthKindsFileTests {
    @Test
    void theShippedFileLoads() {
        var kinds = new HealthKinds(new ClassPathResource("seed/health/kinds.json"));

        assertThat(kinds.all()).hasSize(10);
        assertThat(kinds.find("sleep")).hasValueSatisfying(sleep -> {
            assertThat(sleep.group()).isEqualTo(HealthGroup.SLEEP_HEART);
            assertThat(sleep.dataHints()).containsEntry("hours", "number");
            assertThat(sleep.quantityField()).isEqualTo("hours");
            assertThat(sleep.ranges().get("hours").contains(24)).isTrue();
        });
    }

    @Test
    void aKindListedTwiceIsNamed() {
        assertBroken("""
                {"kinds":[%s, %s]}""".formatted(kind("steps", "activity", "steps", "number"),
                        kind("steps", "activity", "steps", "number")),
                "seed/health/kinds.json: kind steps is listed twice");
    }

    @Test
    void anUnknownGroupIsNamed() {
        assertBroken("""
                {"kinds":[%s]}""".formatted(kind("steps", "heart", "steps", "number")),
                "seed/health/kinds.json: kind steps has an unknown group 'heart'");
    }

    @Test
    void aKindTheDatabaseWouldRejectIsNamed() {
        assertBroken("""
                {"kinds":[%s]}""".formatted(kind("Steps", "activity", "steps", "number")),
                "seed/health/kinds.json: kind 'Steps' must match ^[a-z][a-z0-9_]{1,31}$");
    }

    @Test
    void aMisspelledFieldIsNamedInsteadOfBecomingEmpty() {
        assertBroken("""
                {"kinds":[{"kind":"steps","group":"activity","descripton":"Steps","data_hints":{"steps":"number"}}]}""",
                "seed/health/kinds.json: kind steps needs a description");
    }

    @Test
    void anUnknownFieldTypeIsNamed() {
        assertBroken("""
                {"kinds":[%s]}""".formatted(kind("steps", "activity", "steps", "integer")),
                "seed/health/kinds.json: kind steps field steps has type 'integer'; use number, string or any");
    }

    @Test
    void aRangeOnAFieldThatIsntANumberIsNamed() {
        assertBroken("""
                {"kinds":[{"kind":"workout","group":"workouts","description":"A test kind",
                  "data_hints":{"type":"string"},"ranges":{"type":[0,1]}}]}""",
                "seed/health/kinds.json: kind workout has a range for type, which isn't one of its number fields");
    }

        private static String kind(String kind, String group, String field, String type) {
        return """
                {"kind":"%s","group":"%s","description":"A test kind","data_hints":{"%s":"%s"}}"""
                .formatted(kind, group, field, type);
    }

    private static void assertBroken(String json, String message) {
        assertThatThrownBy(() -> new HealthKinds(new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
    }
}
