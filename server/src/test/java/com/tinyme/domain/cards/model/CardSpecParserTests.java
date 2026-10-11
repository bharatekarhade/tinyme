package com.tinyme.domain.cards.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CardSpecParserTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CardSpecParser parser = new CardSpecParser();
    private final CardSpecWriter writer = new CardSpecWriter();

    @Test
    void parsesTheFourBuiltInCardSpecs() {
        CardSpec journal = parser.parseTrusted(read("""
                {"v":1,"title":"Journal","icon":"journal","accent":"mint","tone":"brand",
                 "badge":{"kind":"streak","block":0},
                 "blocks":[
                   {"type":"stat","label":"entries","metric":{"kinds":["*"],"op":"count"},
                    "period":"range","compare":"previous_period","better":"higher"},
                   {"type":"chart","label":"per day","metric":{"kinds":["*"],"op":"count"},
                    "period":"range","bucket":"auto","style":"bars","highlight":"peak"}]}
                """));
        CardSpec calories = parser.parse(read("""
                {"v":1,"title":"Calories","icon":"flame","accent":"coral","tone":"surface",
                 "badge":{"kind":"period","block":0},
                 "blocks":[
                   {"type":"progress","label":"kcal","style":"gauge",
                    "metric":{"kinds":["meal","drink"],"op":"sum","field":"kcal"},
                    "period":"today","goal":2200,"unit":"kcal"},
                   {"type":"goals","period":"today","items":[
                    {"label":"Protein","metric":{"kinds":["meal","drink"],"op":"sum","field":"protein_g"},"goal":120,"unit":"g"},
                    {"label":"Carbs","metric":{"kinds":["meal","drink"],"op":"sum","field":"carbs_g"},"goal":250,"unit":"g"},
                    {"label":"Fat","metric":{"kinds":["meal","drink"],"op":"sum","field":"fat_g"},"goal":70,"unit":"g"}]}]}
                """));
        CardSpec spending = parser.parse(read("""
                {"v":1,"title":"Spending","icon":"wallet","accent":"sunny","tone":"surface",
                 "badge":{"kind":"delta","block":0},
                 "blocks":[
                   {"type":"stat","label":"spent","metric":{"kinds":["expense"],"op":"sum","field":"amount"},
                    "period":"range","goal":30000,"goal_period":"week","unit":"JPY",
                    "compare":"previous_period","better":"lower"},
                   {"type":"breakdown","label":"by category","metric":{"kinds":["expense"],"op":"sum","field":"amount"},
                    "period":"range","by":"category","top":3,"style":"segmented","unit":"JPY"}]}
                """));
        CardSpec gym = parser.parse(read("""
                {"v":1,"title":"Gym","icon":"dumbbell","accent":"ink","tone":"ink",
                 "blocks":[
                   {"type":"checkins","label":"visits","metric":{"kinds":["workout"],"op":"count"},
                    "period":"range","goal":5,"goal_period":"week"},
                   {"type":"latest","label":"Last session","kinds":["workout"],"fields":["type","minutes"]}]}
                """));

        assertThat(journal.blocks()).hasSize(2);
        assertThat(journal.tone()).isEqualTo(CardTone.BRAND);
        assertThat(calories.blocks().get(0)).isInstanceOf(ProgressBlock.class);
        assertThat(((GoalsBlock) calories.blocks().get(1)).label()).isEqualTo("goals");
        assertThat(spending.badge().kind()).isEqualTo(BadgeKind.DELTA);
        assertThat(gym.blocks().get(1)).isInstanceOf(LatestBlock.class);
        assertThat(writer.write(journal).get("tone").stringValue()).isEqualTo("brand");
        assertThat(writer.write(spending).get("blocks").get(0).get("goal_period").stringValue()).isEqualTo("week");
        assertRoundTrips(journal, true);
        assertRoundTrips(calories, false);
        assertRoundTrips(spending, false);
        assertRoundTrips(gym, false);
    }

    @ParameterizedTest
    @CsvSource({
            "range,day", "range,week", "range,month",
            "this_week,week", "this_week,month",
            "last_7_days,week", "last_7_days,month",
            "this_month,month", "last_30_days,month",
            "last_12_weeks,day", "last_12_weeks,month",
            "this_year,day",
            "today,auto", "today,day", "today,week", "today,month",
            "yesterday,auto", "yesterday,day", "yesterday,week", "yesterday,month"
    })
    void rejectsEachUnsupportedChartBucket(String period, String bucket) {
        String expected = period.equals("today") || period.equals("yesterday")
                ? "use stat or progress for a single day" : "blocks[0] '" + period + "'";
        assertInvalid(chartSpec(period, bucket), expected);
    }

    @Test
    void chartBucketsOwnTheAllowedTableAndAutoResolution() {
        assertThat(ChartBuckets.allowed(com.tinyme.domain.entries.model.aggregate.Period.RANGE))
                .containsExactly(ChartBucket.AUTO);
        assertThat(ChartBuckets.resolveAuto(com.tinyme.domain.entries.model.aggregate.Period.THIS_YEAR, null))
                .isEqualTo(ChartBucket.MONTH);
        assertThat(ChartBuckets.resolveAuto(com.tinyme.domain.entries.model.aggregate.Period.LAST_12_WEEKS, null))
                .isEqualTo(ChartBucket.WEEK);
        assertThat(ChartBuckets.resolveAuto(com.tinyme.domain.entries.model.aggregate.Period.THIS_MONTH, null))
                .isEqualTo(ChartBucket.DAY);
        assertThat(ChartBuckets.resolveAuto(com.tinyme.domain.entries.model.aggregate.Period.RANGE,
                com.tinyme.domain.entries.model.aggregate.Range.YEAR)).isEqualTo(ChartBucket.MONTH);
    }

    @Test
    void chartDefaultsToAutoAndBarsAndOtherBlocksUseTheirSpecifiedStyles() {
        CardSpec chart = parser.parse(read(chartSpec("range", null)));
        ChartBlock chartBlock = (ChartBlock) chart.blocks().getFirst();
        assertThat(chartBlock.bucket()).isEqualTo(ChartBucket.AUTO);
        assertThat(chartBlock.style()).isEqualTo(ChartStyle.BARS);
        CardSpec weeklyAuto = parser.parse(read(chartSpec("this_week", null)));
        assertThat(((ChartBlock) weeklyAuto.blocks().getFirst()).bucket()).isEqualTo(ChartBucket.AUTO);
        assertRoundTrips(weeklyAuto, false);

        CardSpec progress = parser.parse(read("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"progress","label":"x","metric":{"kinds":["drink"],"op":"count"},
                  "period":"today","goal":1}]}
                """));
        assertThat(((ProgressBlock) progress.blocks().getFirst()).style()).isEqualTo(ProgressStyle.RING);

        CardSpec breakdown = parser.parse(read("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"breakdown","label":"x","metric":{"kinds":["expense"],"op":"sum","field":"amount"},
                  "period":"range","by":"category"}]}
                """));
        assertThat(((BreakdownBlock) breakdown.blocks().getFirst()).style()).isEqualTo(BreakdownStyle.BARS);
    }

    @Test
    void addsBlockPathToRecordValidationErrors() {
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"stat","label":"x","metric":{"kinds":["drink"],"op":"count"},
                  "period":"today","goal":0}]}
                """, "blocks[0]: goal must be greater than 0");
    }

    @Test
    void periodBadgeCannotPointAtLatestBlock() {
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "badge":{"kind":"period","block":0},
                 "blocks":[{"type":"latest","label":"x","kinds":["drink"],"fields":["type"]}]}
                """, "period badge requires a block with a period");
    }

    @Test
    void badgeRecordErrorsIncludeBadgePath() {
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "badge":{"kind":"period","block":-1},
                 "blocks":[{"type":"stat","label":"x","metric":{"kinds":["drink"],"op":"count"},
                  "period":"today"}]}
                """, "badge.block must be zero-based");
    }

    @Test
    void enumParsingIsIndependentOfTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            CardSpec card = parser.parse(read("""
                    {"v":1,"title":"x","icon":"chart","accent":"mint","tone":"surface",
                     "blocks":[{"type":"chart","label":"x","metric":{"kinds":["drink"],"op":"count"},
                      "period":"this_week","bucket":"day","style":"line"}]}
                    """));
            assertThat(((ChartBlock) card.blocks().getFirst()).style()).isEqualTo(ChartStyle.LINE);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void unitLengthIsLimitedToEightCharacters() {
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"stat","label":"x","metric":{"kinds":["drink"],"op":"count"},
                  "period":"today","unit":"kilocalories"}]}
                """, "blocks[0].unit must contain 1–8 characters");
    }

    @Test
    void reportsValidBlockTypesForUnknownType() {
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"pie","label":"x"}]}
                """, "valid types: stat, progress, goals, chart, checkins, breakdown, latest");
    }

    @Test
    void reportsValidEnumValues() {
        assertInvalid("""
                {"v":1,"title":"x","icon":"moon","accent":"mint","tone":"surface",
                 "blocks":[{"type":"stat","label":"x","metric":{"kinds":["drink"],"op":"count"},"period":"today"}]}
                """, "valid values: journal, flame, wallet, dumbbell, cup, bed, heart, steps, drop, book, star, chart");
    }

    @Test
    void rejectsTitleAndBlockCountOutsideLimits() {
        assertInvalid("""
                {"v":1,"title":"","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"latest","label":"x","kinds":["drink"],"fields":["type"]}]}
                """, "title must contain 1–40 characters");
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"latest","label":"x","kinds":["drink"],"fields":["type"]},
                           {"type":"latest","label":"x","kinds":["drink"],"fields":["type"]},
                           {"type":"latest","label":"x","kinds":["drink"],"fields":["type"]},
                           {"type":"latest","label":"x","kinds":["drink"],"fields":["type"]},
                           {"type":"latest","label":"x","kinds":["drink"],"fields":["type"]}]}
                """, "blocks must contain 1–4 blocks");
    }

    @Test
    void badgeMustReferenceACompatibleBlock() {
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "badge":{"kind":"period","block":1},
                 "blocks":[{"type":"latest","label":"x","kinds":["drink"],"fields":["type"]}]}
                """, "badge.block must point to an existing block");
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "badge":{"kind":"delta","block":0},
                 "blocks":[{"type":"progress","label":"x","metric":{"kinds":["drink"],"op":"count"},
                  "period":"today","goal":2,"style":"bar"}]}
                """, "delta badge requires a stat block with compare");
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "badge":{"kind":"streak","block":0},
                 "blocks":[{"type":"goals","label":"x","period":"today","items":[
                  {"label":"x","metric":{"kinds":["drink"],"op":"count"},"goal":1}]}]}
                """, "streak badge requires a block with a metric");
    }

    @Test
    void rejectsBrandToneAndAllKindsForNonSystemCards() {
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"brand",
                 "blocks":[{"type":"stat","label":"x","metric":{"kinds":["drink"],"op":"count"},"period":"today"}]}
                """, "tone 'brand' is reserved for system cards");
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"stat","label":"x","metric":{"kinds":["*"],"op":"count"},"period":"today"}]}
                """, "kinds ['*'] is reserved for system cards");
    }

    @Test
    void validatesMetricAndBlockSpecificLimits() {
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"stat","label":"x","metric":{"kinds":["drink"],"op":"sum"},"period":"today"}]}
                """, "metric.field is required for sum");
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"breakdown","label":"x","metric":{"kinds":["expense"],"op":"sum","field":"amount"},
                  "period":"range","by":"category","top":6,"style":"bars"}]}
                """, "top must be between 1 and 5");
        assertInvalid("""
                {"v":1,"title":"x","icon":"star","accent":"mint","tone":"surface",
                 "blocks":[{"type":"latest","label":"x","kinds":["drink"],"fields":["a","b","c","d"]}]}
                """, "latest.fields must contain 1–3 fields");
    }

    private void assertInvalid(String input, String message) {
        assertThatThrownBy(() -> parser.parse(read(input)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private void assertRoundTrips(CardSpec spec, boolean system) {
        CardSpec parsedAgain = system ? parser.parseTrusted(writer.write(spec)) : parser.parse(writer.write(spec));
        assertThat(parsedAgain).isEqualTo(spec);
    }

    private static String chartSpec(String period, String bucket) {
        String bucketJson = bucket == null ? "" : ",\"bucket\":\"" + bucket + "\"";
        return "{\"v\":1,\"title\":\"x\",\"icon\":\"chart\",\"accent\":\"mint\",\"tone\":\"surface\",\"blocks\":["
                + "{\"type\":\"chart\",\"label\":\"x\",\"metric\":{\"kinds\":[\"drink\"],\"op\":\"count\"},"
                + "\"period\":\"" + period + "\"" + bucketJson + "}]}";
    }

    private static tools.jackson.databind.JsonNode read(String json) {
        return JSON.readTree(json);
    }
}
