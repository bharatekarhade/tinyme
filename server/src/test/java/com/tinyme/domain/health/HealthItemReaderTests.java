package com.tinyme.domain.health;

import com.tinyme.domain.health.model.InvalidHealthItemException;
import com.tinyme.domain.health.service.HealthItemReader;
import com.tinyme.domain.health.service.HealthKinds;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthItemReaderTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");
    private final HealthItemReader reader = new HealthItemReader(new HealthKinds(new ClassPathResource("seed/health/kinds.json")));

    @Test
    void aGoodNightReadsWithItsStages() {
        var item = read("""
                {"external_id":"sleep:2026-10-14","kind":"sleep","local_day":"2026-10-14","ts":"2026-10-13T14:40:00Z",
                 "quantity":6.4,"data":{"hours":6.4,"deep_h":1.1,"bed_start":"23:40","bed_end":"07:10",
                 "stages":[["core","23:52","01:10"]]}}""");

        assertThat(item.kind()).isEqualTo("sleep");
        assertThat(item.data()).containsEntry("bed_end", "07:10").containsKey("stages");
    }

    @Test
    void fieldsAreCheckedAgainstTheKind() {
        assertInvalid("""
                {"external_id":"w","kind":"workout","local_day":"2026-10-14","ts":"2026-10-14T01:00:00Z",
                 "quantity":1,"data":{"type":"run","minutes":30,"pace":5}}""", "items[0]: data.pace isn't a workout field");
        assertInvalid("""
                {"external_id":"w","kind":"workout","local_day":"2026-10-14","ts":"2026-10-14T01:00:00Z",
                 "quantity":1,"data":{"type":"run"}}""", "items[0]: workout needs data.minutes");
        assertInvalid("""
                {"external_id":"w","kind":"weight","local_day":"2026-10-14","ts":"2026-10-14T01:00:00Z",
                 "quantity":4.2,"data":{"kg":4.2}}""", "items[0]: data.kg must be between 20 and 400");
        assertInvalid("""
                {"external_id":"w","kind":"workout","local_day":"2026-10-14","ts":"2026-10-14T01:00:00Z",
                 "quantity":"1","data":{"type":"run","minutes":30}}""", "items[0]: quantity must be a number");
    }

    @Test
    void theQuantityIsTheKindsMainNumber() {
        assertInvalid("""
                {"external_id":"s","kind":"steps","local_day":"2026-10-14","ts":"2026-10-14T03:00:00Z",
                 "quantity":10,"data":{"steps":6420}}""", "items[0]: quantity must equal data.steps");
        assertInvalid("""
                {"external_id":"w","kind":"workout","local_day":"2026-10-14","ts":"2026-10-14T01:00:00Z",
                 "quantity":52,"data":{"type":"run","minutes":52}}""", "items[0]: quantity must be 1 for workout");
    }

    @Test
    void tsMustBeNearItsDayAndNotInTheFuture() {
        assertInvalid("""
                {"external_id":"s","kind":"steps","local_day":"2026-10-10","ts":"2026-10-14T03:00:00Z",
                 "quantity":10,"data":{"steps":10}}""", "items[0]: ts is more than a day away from local_day");
        assertInvalid("""
                {"external_id":"s","kind":"steps","local_day":"2026-10-17","ts":"2026-10-17T03:00:00Z",
                 "quantity":10,"data":{"steps":10}}""", "items[0]: ts is in the future");
    }

    @Test
    void onlyHealthKindsAreAccepted() {
        assertInvalid("""
                {"external_id":"c","kind":"drink","local_day":"2026-10-14","ts":"2026-10-14T03:00:00Z",
                 "quantity":1,"data":{}}""", "items[0]: kind drink isn't a Health kind");
    }

    private com.tinyme.domain.health.model.HealthItem read(String json) {
        return reader.read(0, JSON.readTree(json), TOKYO, NOW);
    }

    private void assertInvalid(String json, String message) {
        assertThatThrownBy(() -> read(json))
                .isInstanceOf(InvalidHealthItemException.class)
                .hasMessage(message)
                .satisfies(error -> assertThat(((InvalidHealthItemException) error).index()).isZero());
    }
}
