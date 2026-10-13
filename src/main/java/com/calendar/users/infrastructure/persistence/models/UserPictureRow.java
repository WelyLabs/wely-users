package com.calendar.users.infrastructure.persistence.models;

import java.util.UUID;

/** Projection of app_user for picture lookups. */
public record UserPictureRow(UUID id, String profilePicUrl) {}
