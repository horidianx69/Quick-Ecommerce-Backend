package com.quickcommerce.dispatch;

import com.quickcommerce.work.WorkReports;
import com.quickcommerce.work.WorkReports.Alert;
import com.quickcommerce.work.WorkReports.DriverPay;
import com.quickcommerce.work.WorkReports.ViolationSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin's view of driver work: pay periods, violation alerts, and each driver's record. */
@RestController
public class AdminWorkController {

    public enum AlertAction { DISMISS, SUSPEND }

    public record ResolveRequest(@NotNull AlertAction action) {}

    public record PayPeriodView(WorkReports.Period period, List<DriverPay> drivers, int totalCents) {}

    private final WorkReports reports;
    private final DriverRepository drivers;

    AdminWorkController(WorkReports reports, DriverRepository drivers) {
        this.reports = reports;
        this.drivers = drivers;
    }

    /** What each driver earned in a period. Default: the most recent period that has ended. */
    @GetMapping("/api/admin/payouts")
    PayPeriodView payouts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
        WorkReports.Period period = periodStart == null
                ? reports.periodOf(reports.currentPeriod().start().minusDays(1))
                : reports.periodStarting(periodStart);
        List<DriverPay> rows = reports.pay(period);
        return new PayPeriodView(period, rows, rows.stream().mapToInt(r -> r.statement().totalCents()).sum());
    }

    /** Records that the driver was paid for the period (the transfer itself happens outside the app for now). */
    @PostMapping("/api/admin/payouts/{driverId}/{periodStart}/paid")
    DriverPay markPaid(@PathVariable long driverId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
        return reports.markPaid(driverId, reports.periodStarting(periodStart));
    }

    /** Violation alerts, newest first. DAILY_LIMIT and NOT_RECEIVED are warnings; BAN_REVIEW asks "suspend this driver?". */
    @GetMapping("/api/admin/driver-alerts")
    List<Alert> alerts(@RequestParam(defaultValue = "true") boolean open, @RequestParam(defaultValue = "100") int limit) {
        return reports.alerts(open, Math.clamp(limit, 1, 500));
    }

    /** DISMISS closes the alert. SUSPEND also suspends the driver and takes them offline. */
    @PostMapping("/api/admin/driver-alerts/{id}/resolve")
    @Transactional
    Alert resolve(@PathVariable long id, @Valid @RequestBody ResolveRequest req) {
        long driverId = reports.resolve(id, req.action() == AlertAction.SUSPEND ? "SUSPENDED" : "DISMISSED");
        if (req.action() == AlertAction.SUSPEND) {
            drivers.setStatus(driverId, Driver.Status.SUSPENDED);
            drivers.goOffline(driverId);
        }
        return reports.alerts(false, 500).stream().filter(a -> a.id() == id).findFirst().orElseThrow();
    }

    @GetMapping("/api/admin/drivers/{userId}/violations")
    ViolationSummary violations(@PathVariable long userId, @RequestParam(defaultValue = "30") int days) {
        if (!drivers.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }
        return reports.violations(userId, Math.clamp(days, 1, 365));
    }
}
