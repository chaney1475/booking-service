package com.example.booking.domain.checkout.service;

import com.example.booking.domain.checkout.dto.CheckoutDto;
import com.example.booking.domain.checkout.service.command.CheckoutCommand;

public interface CheckoutService {

    CheckoutDto getCheckout(CheckoutCommand command);
}
