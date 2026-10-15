package com.tinyme.agent.service;

import com.tinyme.agent.model.DeviceToolResult;
import com.tinyme.agent.model.TurnEvent;
import com.tinyme.agent.repository.PendingActionRepository;
import com.tinyme.tools.model.ToolContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Sends a device action over the live turn and waits for the phone's authenticated result. */
@Service
public class DeviceToolGateway {
    private final PendingActionRepository actions;
    private final ConcurrentHashMap<UUID, Pending> waiting = new ConcurrentHashMap<>();

    public DeviceToolGateway(PendingActionRepository actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public DeviceToolResult call(ToolContext turn, String tool, Map<String, Object> input, Duration timeout)
            throws InterruptedException {
        return callWithActionId(turn, tool, input, timeout).result();
    }

    /** Same device round-trip as {@link #call}, also exposing its id for tools that retain the result. */
    public CompletedDeviceAction callWithActionId(ToolContext turn, String tool, Map<String, Object> input,
                                                   Duration timeout) throws InterruptedException {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        if (!turn.turnListener().supportsDeviceActions()) throw new DeviceUnavailableException();

        UUID actionId = actions.insertDelivered(turn.toolCallId(), tool, input, timeout);
        Pending pending = new Pending();
        waiting.put(actionId, pending);
        try {
            turn.turnListener().on(new TurnEvent.ClientAction(actionId, tool, input));
            return new CompletedDeviceAction(actionId, pending.result().get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException timeoutFailure) {
            if (actions.expire(actionId)) throw new DeviceUnavailableException();
            // The phone may have completed the row just as this thread timed out.
            return new CompletedDeviceAction(actionId, getCompleted(pending));
        } catch (InterruptedException interrupted) {
            actions.expire(actionId);
            throw interrupted;
        } catch (ExecutionException impossible) {
            throw new IllegalStateException("Device action result future failed", impossible.getCause());
        } catch (RuntimeException deliveryFailure) {
            actions.expire(actionId);
            throw new DeviceUnavailableException(deliveryFailure);
        } finally {
            waiting.remove(actionId, pending);
        }
    }

    public record CompletedDeviceAction(UUID actionId, DeviceToolResult result) { }

    /** Accepts one result from the phone. Only an action currently awaited by this server can be completed. */
    public void submitResult(UUID actionId, DeviceToolResult result) {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(result, "result");
        Pending pending = waiting.get(actionId);
        if (pending == null) throw closedAction(actionId);
        if (!actions.complete(actionId, result)) throw closedAction(actionId);
        pending.result().complete(result);
        waiting.remove(actionId, pending);
    }

    private DeviceToolResult getCompleted(Pending pending) throws InterruptedException {
        try {
            return pending.result().get();
        } catch (ExecutionException impossible) {
            throw new IllegalStateException("Device action result future failed", impossible.getCause());
        }
    }

    private DeviceActionException closedAction(UUID id) {
        return actions.status(id).filter(status -> List.of("succeeded", "failed", "expired").contains(status))
                .<DeviceActionException>map(status -> new DeviceActionConflictException())
                .orElseGet(DeviceActionNotFoundException::new);
    }

    private static final class Pending {
        private final CompletableFuture<DeviceToolResult> result = new CompletableFuture<>();
        CompletableFuture<DeviceToolResult> result() { return result; }
    }

    public static class DeviceActionException extends RuntimeException {
        protected DeviceActionException(String message) { super(message); }
    }

    public static final class DeviceUnavailableException extends RuntimeException {
        public DeviceUnavailableException() { super("The phone could not answer this action in time"); }
        public DeviceUnavailableException(Throwable cause) { super("The phone could not answer this action", cause); }
    }

    public static final class DeviceActionNotFoundException extends DeviceActionException {
        public DeviceActionNotFoundException() { super("Device action not found"); }
    }

    public static final class DeviceActionConflictException extends DeviceActionException {
        public DeviceActionConflictException() { super("Device action has already completed or expired"); }
    }
}
