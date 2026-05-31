package com.example.booking.common.entity;

import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@MappedSuperclass
public abstract class SoftDeletableEntity extends BaseEntity {

    private ZonedDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    protected void softDelete() {
        this.deletedAt = ZonedDateTime.now();
    }
}
