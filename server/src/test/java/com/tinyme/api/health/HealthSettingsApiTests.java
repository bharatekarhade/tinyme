package com.tinyme.api.health;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.auth.service.DeviceTokenService;
import com.tinyme.domain.health.model.HealthGroup;
import com.tinyme.domain.health.model.HealthNudgeTopic;
import com.tinyme.domain.health.repository.HealthSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@AutoConfigureMockMvc
@Import(AgentDatabaseConfiguration.class)
class HealthSettingsApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceTokenService tokens;
    @Autowired HealthSettingsRepository settings;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM health_settings");
    }

    @Test
    void settingsNeedAToken() throws Exception {
        mvc.perform(put("/health/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groups\":[],\"write_enabled\":false,\"nudges_off\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSwitchesAreStoredAndReplacedAsAWhole() throws Exception {
        assertThat(settings.load().groups()).isEmpty();

        putSettings("{\"groups\":[\"activity\",\"sleep_heart\"],\"write_enabled\":true,\"nudges_off\":[\"weight\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0]").value("activity"))
                .andExpect(jsonPath("$.write_enabled").value(true))
                .andExpect(jsonPath("$.nudges_off[0]").value("weight"));
        assertThat(settings.load().groups()).containsExactly(HealthGroup.ACTIVITY, HealthGroup.SLEEP_HEART);
        assertThat(settings.load().nudgesOff()).containsExactly(HealthNudgeTopic.WEIGHT);

        putSettings("{\"groups\":[\"workouts\"],\"write_enabled\":false,\"nudges_off\":[]}").andExpect(status().isOk());
        assertThat(settings.load().groups()).containsExactly(HealthGroup.WORKOUTS);
        assertThat(settings.load().writeEnabled()).isFalse();
        assertThat(settings.load().nudgesOff()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM health_settings", Long.class)).isEqualTo(1);
    }

    @Test
    void unknownGroupsAndTopicsAreRefused() throws Exception {
        putSettings("{\"groups\":[\"heart\"],\"write_enabled\":false,\"nudges_off\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("groups: unknown group heart; use activity, workouts, body or sleep_heart"));
        putSettings("{\"groups\":[],\"write_enabled\":false,\"nudges_off\":[\"body\"]}")
                .andExpect(status().isBadRequest());
        putSettings("{\"groups\":[]}")
                .andExpect(status().isBadRequest());
    }

    private ResultActions putSettings(String body) throws Exception {
        return mvc.perform(put("/health/settings")
                .header("Authorization", "Bearer " + tokens.issue("health-settings-tests").token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
