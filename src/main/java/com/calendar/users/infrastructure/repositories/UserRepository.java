package com.calendar.users.infrastructure.repositories;

import com.calendar.users.infrastructure.models.entities.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    @Query("SELECT f.friend FROM FriendshipEntity f WHERE f.user.id = :userId")
    Page<UserEntity> findByUserId(@Param("userId") Long userId, Pageable pageable);
}
