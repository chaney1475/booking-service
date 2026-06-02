package com.example.booking.payment.repository;

import com.example.booking.payment.entity.PaymentLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentLineRepository extends JpaRepository<PaymentLine, Long> {

    List<PaymentLine> findByPaymentId(Long paymentId);
}
