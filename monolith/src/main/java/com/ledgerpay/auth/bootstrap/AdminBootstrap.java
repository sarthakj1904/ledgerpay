package com.ledgerpay.auth.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.auth.domain.Role;
import com.ledgerpay.auth.domain.UserAccount;
import com.ledgerpay.auth.domain.UserRepository;

/**
 * Creates a bootstrap ADMIN user the first time the application starts. The credentials are
 * configurable via {@code ledgerpay.bootstrap.admin.*} properties so production deployments can
 * inject their own and rotate them. The user is only created if no row with the configured
 * email exists, so re-running is idempotent.
 */
@Component
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final boolean enabled;

    public AdminBootstrap(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          @Value("${ledgerpay.bootstrap.admin.enabled:true}") boolean enabled,
                          @Value("${ledgerpay.bootstrap.admin.email:admin@ledgerpay.local}") String adminEmail,
                          @Value("${ledgerpay.bootstrap.admin.password:admin12345}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedAdmin() {
        if (!enabled) {
            return;
        }
        if (userRepository.existsByEmail(adminEmail.toLowerCase())) {
            log.debug("Bootstrap admin {} already exists; skipping", adminEmail);
            return;
        }
        UserAccount admin = UserAccount.create(adminEmail,
                passwordEncoder.encode(adminPassword), Role.ADMIN);
        userRepository.save(admin);
        log.warn("Bootstrap ADMIN user created: email={} (rotate the password in production)",
                adminEmail);
    }
}
