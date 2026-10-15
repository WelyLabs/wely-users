package com.calendar.users.infrastructure.persistence.models.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of {@code account_deletion}, inserted by @Query because a preassigned id would make save() update. */
@Setter
@Getter
@Builder
@Table("account_deletion")
@AllArgsConstructor
@NoArgsConstructor
public class AccountDeletionEntity {

    @Id
    @Column("user_id")
    private UUID userId;

    @Column("keycloak_id")
    private String keycloakId;

    @Column("requested_at")
    private OffsetDateTime requestedAt;

    @Column("identity_deleted_at")
    private OffsetDateTime identityDeletedAt;

    @Column("media_deleted_at")
    private OffsetDateTime mediaDeletedAt;

    @Column("attempts")
    private Integer attempts;

    @Column("next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column("last_error")
    private String lastError;
}
