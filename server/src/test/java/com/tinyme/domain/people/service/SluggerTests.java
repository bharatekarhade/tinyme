package com.tinyme.domain.people.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SluggerTests {
    @Test
    void createsSlugFromSimpleName() {
        assertThat(Slugger.base("Kenji")).isEqualTo("kenji");
    }

    @Test
    void separatesNamePartsWithHyphens() {
        assertThat(Slugger.base("Kenji Tanaka")).isEqualTo("kenji-tanaka");
    }

    @Test
    void removesDiacritics() {
        assertThat(Slugger.base("José")).isEqualTo("jose");
    }

    @Test
    void usesFallbackForNamesWithoutLatinCharacters() {
        assertThat(Slugger.base("健二")).isEqualTo("person");
    }

    @Test
    void truncatesLongNamesToFortyCharactersWithoutTrailingHyphen() {
        String slug = Slugger.base("a".repeat(60));

        assertThat(slug).hasSizeLessThanOrEqualTo(40).doesNotEndWith("-");
    }
}
