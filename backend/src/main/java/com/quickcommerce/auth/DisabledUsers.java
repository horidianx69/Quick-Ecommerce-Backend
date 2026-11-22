package com.quickcommerce.auth;

import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The ids of suspended users, kept in memory so every request can be checked
 * without a database query. Loaded on first use, refreshed every 30 seconds,
 * and refreshed at once on the server that handles a suspension. With several
 * servers, the others catch up within 30 seconds.
 */
@Component
public class DisabledUsers {

    private final UserRepository users;
    private volatile Set<Long> ids;

    DisabledUsers(UserRepository users) {
        this.users = users;
    }

    public boolean contains(long userId) {
        Set<Long> current = ids;
        return (current != null ? current : load()).contains(userId);
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void refresh() {
        load();
    }

    private Set<Long> load() {
        Set<Long> fresh = Set.copyOf(users.disabledIds());
        ids = fresh;
        return fresh;
    }
}
