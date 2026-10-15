package com.calendar.users.infrastructure.persistence.models.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/** One row of {@code retired_handle}, the digest of a deleted account's handle, written by @Query only. */
@Setter
@Getter
@Table("retired_handle")
@AllArgsConstructor
@NoArgsConstructor
public class RetiredHandleEntity {

    @Id
    @Column("handle_digest")
    private byte[] handleDigest;

    @Column("retired_at")
    private OffsetDateTime retiredAt;
}
