package com.tinyme.agent.model;

import java.util.UUID;

public record SessionRef(UUID sessionRowId, String anthropicSessionId) {
}
