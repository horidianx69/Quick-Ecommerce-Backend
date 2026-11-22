package com.quickcommerce.auth;

import com.quickcommerce.auth.User.Role;
import com.quickcommerce.common.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin's list of accounts, and suspending one. Shops and drivers also have their own approval status. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    public record AdminUserView(Long id, String email, String name, Role role, boolean disabled, Instant createdAt) {
        static AdminUserView of(User u) {
            return new AdminUserView(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.isDisabled(), u.getCreatedAt());
        }
    }

    public record DisabledRequest(@NotNull Boolean disabled) {}

    private final UserRepository users;
    private final DisabledUsers disabledUsers;

    AdminUserController(UserRepository users, DisabledUsers disabledUsers) {
        this.users = users;
        this.disabledUsers = disabledUsers;
    }

    /** All accounts, oldest first. q searches email and name; role narrows to one role. */
    @GetMapping
    Page<AdminUserView> list(@RequestParam(required = false) Role role, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        String term = q.trim().toLowerCase(Locale.ROOT);
        if (term.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is too long");
        }
        String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return users.search(role == null ? EnumSet.allOf(Role.class) : EnumSet.of(role), pattern, Paging.of(page, size))
                .map(AdminUserView::of);
    }

    /**
     * Suspends ({"disabled": true}) or restores an account. A suspended user
     * cannot log in, and tokens they already hold stop working at once.
     * Admin accounts cannot be suspended here.
     */
    @PutMapping("/{id}/disabled")
    @Transactional
    AdminUserView setDisabled(@PathVariable Long id, @Valid @RequestBody DisabledRequest req) {
        User user = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Admin accounts cannot be suspended");
        }
        users.setDisabled(id, req.disabled(), Role.ADMIN);
        disabledUsers.refresh();
        return AdminUserView.of(users.findById(id).orElseThrow());
    }
}
