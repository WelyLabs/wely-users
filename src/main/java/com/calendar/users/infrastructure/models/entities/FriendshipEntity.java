package com.calendar.users.infrastructure.models.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table("user_friends")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class FriendshipEntity {

    @Id // Annotation R2DBC
    private Long id;

    // Remplacement des annotations @ManyToOne et @JoinColumn par des IDs simples
    // Les jointures seront gérées par le Repository (ou une logique Service)
    @Column("user_id")
    private Long userId;

    @Column("friend_id")
    private Long friendId;

    // Constructeur adapté pour R2DBC (utilisation des IDs)
    public FriendshipEntity(Long userId, Long friendId) {
        this.userId = userId;
        this.friendId = friendId;
    }
}