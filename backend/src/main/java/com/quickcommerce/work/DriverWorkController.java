package com.quickcommerce.work;

import com.quickcommerce.work.WorkReports.PayStatement;
import com.quickcommerce.work.WorkReports.ViolationSummary;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The driver's own pay and record. */
@RestController
@RequestMapping("/api/drivers/me")
public class DriverWorkController {

    private final WorkReports reports;

    DriverWorkController(WorkReports reports) {
        this.reports = reports;
    }

    /** Two-week pay statements, the current period first: hours, km, pay, tips, and whether it was paid. */
    @GetMapping("/pay-statements")
    List<PayStatement> payStatements(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "6") int count) {
        return reports.statements(Long.parseLong(jwt.getSubject()), Math.clamp(count, 1, 26));
    }

    /** Violations today and in the last 30 days, against the limits, with the latest ones listed. */
    @GetMapping("/violations")
    ViolationSummary violations(@AuthenticationPrincipal Jwt jwt) {
        return reports.violations(Long.parseLong(jwt.getSubject()), 30);
    }
}
