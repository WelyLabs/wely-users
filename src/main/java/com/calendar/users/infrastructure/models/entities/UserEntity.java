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
@Table("app_user") // Renommage pour éviter les conflits avec le mot-clé SQL 'user'
@AllArgsConstructor
@NoArgsConstructor
public class UserEntity {

    @Id // Annotation R2DBC
    private Long id;

    @Column("keycloak_id")
    private String keycloakId;

    @Column("joined_date")
    private LocalDateTime joinedDate;

    // R2DBC ne gère pas directement les relations complexes comme JPA.
    // La gestion de la liste d'amis doit se faire via des IDs
    // ou une logique de Service.

    // Nous utilisons @Transient pour indiquer que ce champ n'est PAS mappé à la DB.
    @Transient
    private Set<FriendshipEntity> friendships = new HashSet<>();

    // Note : Les méthodes addFriend/removeFriend devront être adaptées
    // pour travailler avec le Repository R2DBC (Mono/Flux) dans la couche Service.
}