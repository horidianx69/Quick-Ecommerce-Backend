package com.quickcommerce.auth;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** pattern is a LIKE pattern, already lower-cased and escaped; "%" matches everyone. */
    @Query("""
            select u from User u
            where u.role in :roles and (lower(u.email) like :pattern or lower(u.name) like :pattern)
            order by u.id
            """)
    Page<User> search(Collection<User.Role> roles, String pattern, Pageable pageable);

    /** Admin accounts can never be suspended this way, so admin cannot lock everyone out. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.disabled = :disabled where u.id = :id and u.role <> :admin")
    int setDisabled(Long id, boolean disabled, User.Role admin);

    @Query("select u.id from User u where u.disabled = true")
    List<Long> disabledIds();
}
