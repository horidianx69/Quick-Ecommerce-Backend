package com.quickcommerce.payment;

import com.quickcommerce.work.WorkReports;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/stripe")
@ConditionalOnProperty(name = "app.payments.enabled", havingValue = "true")
class StripeSettlementController {

    record LinkRequest(@NotBlank @Size(max = 69) String accountId) {}

    private final StripeSettlements settlements;
    private final WorkReports reports;

    StripeSettlementController(StripeSettlements settlements, WorkReports reports) {
        this.settlements = settlements;
        this.reports = reports;
    }

    @PutMapping("/recipients/{kind}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void link(@PathVariable StripeSettlements.Kind kind, @PathVariable long id, @Valid @RequestBody LinkRequest body) {
        settlements.link(kind, id, body.accountId());
    }

    @PostMapping("/orders/{id}/transfer")
    @ResponseStatus(HttpStatus.ACCEPTED)
    StripeSettlements.Settlement shop(@PathVariable long id) {
        return settlements.queueShop(id);
    }

    @PostMapping("/driver-payouts/{driverId}/{periodStart}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    StripeSettlements.Settlement driver(@PathVariable long driverId, @PathVariable LocalDate periodStart) {
        return settlements.queueDriver(driverId, reports.periodStarting(periodStart));
    }

    @GetMapping("/transfer-reviews")
    List<StripeSettlements.Settlement> transferReviews(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return settlements.needsReview(page, size);
    }

    @GetMapping("/transfers/{id}")
    StripeSettlements.Settlement get(@PathVariable UUID id) {
        return settlements.get(id);
    }
}
