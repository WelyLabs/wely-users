package com.calendar.users.domain.ports;

/**
 * Product facts this service establishes, reported the moment they are true: never on a failure,
 * never twice for a retried call. What becomes of them — a counter on a dashboard — is the
 * adapter's business; the domain only says what happened.
 */
public interface UsersActivity {

    /** A new user was created at registration; a retried provisioning of the same user is not one. */
    void userSignedUp();

    /** An account was turned into a tombstone at its owner's request. */
    void deletionRequested();

    /** The deletion of an account finished: its identity and its pictures are gone too. */
    void deletionCompleted();
}
