package com.example.booking.user.repository;

import com.example.booking.user.entity.UserPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserPointRepository extends JpaRepository<UserPoint, Long> {

    Optional<UserPoint> findByUserId(Long userId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserPoint up SET up.balance = up.balance - :amount, up.updatedAt = CURRENT_TIMESTAMP WHERE up.userId = :userId AND up.balance >= :amount")
    int deductBalance(@Param("userId") Long userId, @Param("amount") long amount);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserPoint up SET up.balance = up.balance + :amount, up.updatedAt = CURRENT_TIMESTAMP WHERE up.userId = :userId")
    int refundBalance(@Param("userId") Long userId, @Param("amount") long amount);
}
