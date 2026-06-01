package com.example.booking.event.repository;

import com.example.booking.event.entity.EventOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EventOptionRepository extends JpaRepository<EventOption, Long> {

    @Query("""
            select eo from EventOption eo
            join fetch eo.event
            join fetch eo.option o
            join fetch o.product
            where eo.event.id = :eventId and eo.option.id = :optionId
            """)
    Optional<EventOption> findByEventIdAndOptionId(
            @Param("eventId") Long eventId,
            @Param("optionId") Long optionId
    );

    @Query("select eo from EventOption eo join fetch eo.option where eo.event.id = :eventId")
    List<EventOption> findByEventId(@Param("eventId") Long eventId);
}
