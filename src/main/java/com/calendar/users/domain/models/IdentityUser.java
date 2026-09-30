package com.calendar.users.domain.models;

/**
 * A user as the identity provider knows them, in terms the domain owns.
 *
 * <p>The {@code IdentityProvider} port used to return {@code KeycloakUserResponse}, an
 * infrastructure record named after the vendor. That inverted the dependency the hexagon
 * exists to establish: the domain could not be compiled or tested without the Keycloak
 * client on the classpath, and swapping identity providers would have meant editing the
 * domain.
 *
 * <p>Only the fields the domain actually uses are here. The wire payload carries an email
 * and a verification flag as well; those stay in the adapter, because nothing in this
 * service reads them and a port should not promise data no one consumes.
 */
public record IdentityUser(
        String username,
        String firstName,
        String lastName
) {}
