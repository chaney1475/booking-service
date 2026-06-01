package com.example.booking.checkout.service;

import com.example.booking.checkout.dto.CheckoutDto;
import com.example.booking.checkout.service.command.CheckoutCommand;

public interface CheckoutService {

    CheckoutDto getCheckout(CheckoutCommand command);
}
