package com.calendar.users.infrastructure.persistence.repositories;

import com.calendar.users.infrastructure.persistence.models.entities.RetiredHandleEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Spring Data access to {@code retired_handle}; the digest is computed by retired_handle_digest (V6). */
@Repository
public interface RetiredHandleR2dbcRepository extends ReactiveCrudRepository<RetiredHandleEntity, byte[]> {

    /** Retires the live user's current handle; must run before the tombstone overwrites it. */
    @Modifying
    @Query("""
            INSERT INTO retired_handle (handle_digest)
            SELECT retired_handle_digest(user_name, hashtag)
              FROM app_user
             WHERE id = :userId
               AND deleted_at IS NULL
            ON CONFLICT (handle_digest) DO NOTHING
            """)
    Mono<Long> retireHandleOf(UUID userId);

    @Query("SELECT EXISTS (SELECT 1 FROM retired_handle WHERE handle_digest = retired_handle_digest(:userName, :hashtag))")
    Mono<Boolean> isRetired(String userName, Integer hashtag);
}
