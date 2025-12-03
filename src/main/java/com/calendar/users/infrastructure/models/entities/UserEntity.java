package com.calendar.users.infrastructure.models.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Setter
@Getter
@Table("app_user")
@AllArgsConstructor
@NoArgsConstructor
public class UserEntity {

    @Id
    private Long id;

    @Column("keycloak_id")
    private String keycloakId;

    @Column("profile_pic_url")
    private String profilePicUrl;

    @Column("joined_date")
    private LocalDateTime joinedDate;

    @Transient
    private Set<FriendshipEntity> friendships = new HashSet<>();
}