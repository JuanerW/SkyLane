package com.skylane.quote;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record QuoteRequest(
        @NotNull @Valid Item item,
        @NotNull @DecimalMin("0.01") @DecimalMax("10") @Digits(integer = 2, fraction = 2) Double weightKg,
        @NotNull @Valid Place pickup,
        @NotNull @Valid Place dropoff,
        @Size(max = 500) String notes,
        @Positive Integer arriveWithinMinutes) {

    public record Item(@NotBlank String description, boolean fragile) {
    }

    public record Place(@NotBlank String text, @NotNull Double lat, @NotNull Double lng) {
    }
}
