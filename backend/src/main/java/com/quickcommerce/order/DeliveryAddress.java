package com.quickcommerce.order;

import com.quickcommerce.common.Canada;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Where the order goes. latitude/longitude come from the customer's map pin. */
public record DeliveryAddress(
        @NotBlank @Size(max = 200) String addressLine,
        @NotBlank @Size(max = 80) String city,
        @NotBlank @Pattern(regexp = Canada.PROVINCE, message = "must be a Canadian province code") String province,
        @NotBlank @Pattern(regexp = Canada.POSTAL_CODE, message = "must be a Canadian postal code") String postalCode,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}
