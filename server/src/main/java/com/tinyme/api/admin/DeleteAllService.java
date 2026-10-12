package com.tinyme.api.admin;

import com.tinyme.agent.bootstrap.MemorySeedFiles;
import com.tinyme.agent.client.ManagedAgentApi;
import com.tinyme.agent.client.ManagedAgents;
import com.tinyme.agent.service.AgentResources;
import com.tinyme.agent.repository.AgentSessionRepository;
import com.tinyme.auth.repository.AccountRepository;
import com.tinyme.auth.service.AuthException;
import com.tinyme.auth.service.LoginRateLimiter;
import com.tinyme.auth.service.PasswordService;
import com.tinyme.domain.cards.service.CardBootstrap;
import com.tinyme.domain.admin.UserDataTables;
import com.tinyme.maintenance.MaintenanceGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Coordinates the remote-first delete-all operation; local data changes only after remote cleanup succeeds. */
@Service
public class DeleteAllService {
    private static final Logger log = LoggerFactory.getLogger(DeleteAllService.class);
    private static final String CONFIRMATION = "DELETE EVERYTHING";
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final ManagedAgents agents;
    private final AgentResources resources;
    private final AgentSessionRepository sessions;
    private final MemorySeedFiles memorySeeds;
    private final AccountRepository accounts;
    private final PasswordService passwords;
    private final LoginRateLimiter rateLimiter;
    private final MaintenanceGate gate;
    private final com.tinyme.agent.service.SessionTurnLock turnLocks;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CardBootstrap cardBootstrap;

    public DeleteAllService(ManagedAgents agents, AgentResources resources, AgentSessionRepository sessions,
                            MemorySeedFiles memorySeeds, AccountRepository accounts, PasswordService passwords,
                            LoginRateLimiter rateLimiter, MaintenanceGate gate,
                            com.tinyme.agent.service.SessionTurnLock turnLocks, JdbcTemplate jdbc,
                            TransactionTemplate transactions, CardBootstrap cardBootstrap) {
        this.agents = agents;
        this.resources = resources;
        this.sessions = sessions;
        this.memorySeeds = memorySeeds;
        this.accounts = accounts;
        this.passwords = passwords;
        this.rateLimiter = rateLimiter;
        this.gate = gate;
        this.turnLocks = turnLocks;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cardBootstrap = cardBootstrap;
    }

    public DeleteAllCounts deleteAll(String confirm, String password, short ownerId, String remoteIp) {
        if (!CONFIRMATION.equals(confirm)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "confirm must exactly match DELETE EVERYTHING");
        }
        if (!rateLimiter.tryAcquire(remoteIp)) {
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts");
        }
        var account = accounts.findById(ownerId).orElse(null);
        if (account == null || !passwords.verify(password, account.getPasswordHash())) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        if (!gate.beginMaintenance(turnLocks.hasActiveTurn())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                    "A reply is still running");
        }
        try {
            var memoryStoreId = resources.load().memoryStoreId();
            List<String> sessionIds = sessions.findAllAnthropicSessionIds();
            long entries = count("entries");
            long people = count("people");
            long cards = jdbc.queryForObject("SELECT count(*) FROM cards WHERE system = false", Long.class);
            long messages = count("messages");

            final long memoryDocuments;
            try {
                deleteSessions(sessionIds);
                memoryDocuments = resetMemories(memoryStoreId);
            } catch (IOException | InterruptedException | RuntimeException remoteFailure) {
                if (remoteFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw remoteFailure(remoteFailure);
            }

            transactions.executeWithoutResult(status -> {
                jdbc.execute("TRUNCATE " + String.join(", ", UserDataTables.ALL)
                        + " RESTART IDENTITY CASCADE");
                try {
                    cardBootstrap.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
                } catch (Exception failure) {
                    throw new IllegalStateException("Could not reseed the Journal card", failure);
                }
            });
            var counts = new DeleteAllCounts(entries, people, cards, messages, sessionIds.size(), memoryDocuments);
            log.info("Delete-all completed: entries={}, people={}, cards={}, messages={}, sessions={}, memory_documents={}",
                    entries, people, cards, messages, sessionIds.size(), memoryDocuments);
            return counts;
        } finally {
            gate.endMaintenance();
        }
    }

    private void deleteSessions(List<String> sessionIds) throws IOException, InterruptedException {
        for (String id : sessionIds) {
            try {
                agents.deleteSession(id);
            } catch (ManagedAgentApi.ApiException failure) {
                if (failure.status() != 404) throw failure;
            }
            sessions.markDeleted(id);
        }
    }

    private long resetMemories(String memoryStoreId) throws IOException, InterruptedException {
        List<com.tinyme.agent.client.MemoryRef> all = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            var page = agents.listMemories(memoryStoreId, "/", cursor);
            all.addAll(page.memories());
            cursor = page.nextCursor();
            if (cursor != null && !cursors.add(cursor)) throw new IOException("Memory pagination repeated a cursor");
        } while (cursor != null);

        for (var memory : all) {
            try {
                agents.deleteMemory(memoryStoreId, memory.id());
            } catch (ManagedAgentApi.ApiException failure) {
                if (failure.status() != 404) throw failure;
            }
        }
        for (var seed : memorySeeds.load().entrySet()) {
            try {
                agents.createMemory(memoryStoreId, seed.getKey(), seed.getValue());
            } catch (ManagedAgentApi.ApiException failure) {
                // Makes a retry safe if a previous reset created some templates before failing.
                if (failure.status() != 409 || !failure.type().equals("memory_path_conflict_error")) throw failure;
            }
        }
        return all.size();
    }

    private long count(String table) {
        // Only internal, fixed table names are passed here.
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static org.springframework.web.server.ResponseStatusException remoteFailure(Exception failure) {
        log.warn("Delete-all stopped before the local database wipe because remote cleanup failed", failure);
        return new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Could not clear Anthropic sessions or memory. Your local data is unchanged; try again.", failure);
    }
}
