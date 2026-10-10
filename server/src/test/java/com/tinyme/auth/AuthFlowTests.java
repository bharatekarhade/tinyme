package com.tinyme.auth;

import com.tinyme.auth.entity.DeviceTokenEntity;
import com.tinyme.auth.repository.AccountRepository;
import com.tinyme.auth.repository.DeviceTokenRepository;
import com.tinyme.auth.service.AccountBootstrap;
import com.tinyme.auth.service.DeviceTokenService;
import com.tinyme.auth.service.PasswordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@AutoConfigureMockMvc
@Import(AuthFlowTests.DatabaseConfiguration.class)
class AuthFlowTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceTokenService tokens;
    @Autowired AccountRepository accounts;
    @Autowired DeviceTokenRepository tokenRepository;
    @Autowired PasswordService passwords;

    @Test
    void loginIgnoresEmailCaseAndStoresOnlyTheTokenHash() throws Exception {
        var response = mvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"  OWNER@Test.TINYME.LOCAL ","password":"test-only-owner-password","device_name":"phone"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expires_at").isString())
                .andReturn();
        String raw = com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.token");
        assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
        byte[] stored = jdbc.queryForObject("SELECT token_hash FROM refresh_tokens WHERE device_name = 'phone'", byte[].class);
        assertThat(stored).containsExactly(hash(raw));
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain(raw);
    }

    @Test
    void wrongEmailAndWrongPasswordReturnTheSameUnauthorizedProblem() throws Exception {
        String wrongEmail = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"email\":\"elsewhere@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String wrongPassword = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"email\":\"owner@test.tinyme.local\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(wrongEmail).isEqualTo(wrongPassword);
        assertThat(wrongEmail).contains("Invalid email or password");
    }

    @Test
    void invalidOrOversizedLoginBodiesReturnBadRequestBeforePasswordVerification() throws Exception {
        mvc.perform(post("/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"email\":\"owner@example.com\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"email\":\"owner@example.com\",\"password\":\"" + "x".repeat(10_000) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("x".repeat(100)))));
    }

    @Test
    void healthIsPublicAndOtherPathsRequireAValidBearerToken() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"));
        mvc.perform(post("/auth/logout").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/logout").header("Authorization", "bearer xyz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticationRejectsMissingUnknownRevokedAndExpiredTokensAndLogoutIsPerDevice() throws Exception {
        mvc.perform(post("/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer unknown"))
                .andExpect(status().isUnauthorized());

        var first = tokens.issue("first phone").token();
        var second = tokens.issue("second phone").token();
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + first))
                .andExpect(status().isNoContent());
        assertThat(tokens.verify(first)).isEmpty();
        assertThat(tokens.verify(second)).isPresent();
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + first))
                .andExpect(status().isUnauthorized());

        var expired = "expired-test-token";
        tokenRepository.save(DeviceTokenEntity.issue(hash(expired), "old phone", Instant.now().minusSeconds(1)));
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sixthFailedLoginWithinAMinuteIsRateLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/auth/login").with(request -> { request.setRemoteAddr("192.0.2.44"); return request; })
                            .contentType("application/json")
                            .content("{\"email\":\"wrong@example.com\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/auth/login").with(request -> { request.setRemoteAddr("192.0.2.44"); return request; })
                        .contentType("application/json")
                        .content("{\"email\":\"wrong@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void bootstrapDoesNotChangeThePasswordWhenTheConfiguredPasswordChanges() throws Exception {
        var original = accounts.findByEmail("owner@test.tinyme.local").orElseThrow().getPasswordHash();
        var restarted = new AccountBootstrap(accounts, passwords, "different@example.com", "different-password");
        ApplicationArguments args = new DefaultApplicationArguments(new String[0]);
        restarted.run(args);
        var account = accounts.findByEmail("owner@test.tinyme.local").orElseThrow();
        assertThat(account.getPasswordHash()).isEqualTo(original);
        assertThat(passwords.verify("test-only-owner-password", original)).isTrue();
        assertThat(accounts.findByEmail("different@example.com")).isEmpty();
    }

    @Test
    void emptyDatabaseWithoutOwnerConfigurationFailsStartupBootstrap() {
        var emptyAccounts = mock(AccountRepository.class);
        when(emptyAccounts.exists()).thenReturn(false);
        var bootstrap = new AccountBootstrap(emptyAccounts, passwords, " ", " ");
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TINYME_OWNER_EMAIL and TINYME_OWNER_PASSWORD");
    }

    @Test
    void bootstrapRejectsWeakPasswordAndMalformedEmail() {
        var emptyAccounts = mock(AccountRepository.class);
        when(emptyAccounts.exists()).thenReturn(false);
        var weakPassword = new AccountBootstrap(emptyAccounts, passwords, "owner@example.com", "short");
        assertThatThrownBy(() -> weakPassword.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("12 and 256 characters");
        var malformedEmail = new AccountBootstrap(emptyAccounts, passwords, "owner-without-domain", "strong-password-123");
        assertThatThrownBy(() -> malformedEmail.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("valid email address");
    }

    private static byte[] hash(String raw) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"));
        }
    }
}
