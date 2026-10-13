package com.calendar.users.domain.models;

/** What a user may change about themselves. The email stays with the identity provider. */
public record ProfileUpdate(String userName, String firstName, String lastName) {
}
