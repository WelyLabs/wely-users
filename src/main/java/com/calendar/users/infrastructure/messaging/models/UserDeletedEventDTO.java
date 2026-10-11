package com.calendar.users.infrastructure.messaging.models;

import java.util.UUID;

public record UserDeletedEventDTO(UUID userId) {
}
