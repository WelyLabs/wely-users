package com.calendar.users.infrastructure.persistence.models.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
@Table("app_user")
@AllArgsConstructor
@NoArgsConstructor
public class UserEntity {

    @Id
    private UUID id;

    @Column("keycloak_id")
    private String keycloakId;

    @Column("user_name")
    private String userName;

    @Column("hashtag")
    private Integer hashtag;

    @Column("first_name")
    private String firstName;

    @Column("last_name")
    private String lastName;

    @Column("profile_pic_url")
    private String profilePicUrl;

    @Column("joined_date")
    private LocalDateTime joinedDate;
}