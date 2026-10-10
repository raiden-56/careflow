package com.careflow.hms.seed;

import com.careflow.hms.auth.TokenHasher;
import com.careflow.hms.config.CareFlowProperties;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.RolePermissionService;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ensures the platform organization and an initial super administrator exist.
 * Credentials come from CAREFLOW_SUPERADMIN_EMAIL / CAREFLOW_SUPERADMIN_PASSWORD. In demo mode a
 * documented development-only password is used when none is supplied; otherwise a random password
 * is set and the operator must use account recovery or configure the environment variable.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PlatformBootstrap implements ApplicationRunner {

    public static final String DEMO_PASSWORD = "CareFlow#Demo2026";

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final RolePermissionService rolePermissions;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final CareFlowProperties props;

    @Value("${CAREFLOW_SUPERADMIN_EMAIL:superadmin@careflow.local}")
    private String superAdminEmail;

    @Value("${CAREFLOW_SUPERADMIN_PASSWORD:}")
    private String superAdminPassword;

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(status -> {
            Organization platform = organizations.findFirstByPlatformTrue().orElseGet(() -> {
                Organization o = new Organization();
                o.setCode("PLATFORM");
                o.setName("CareFlow Platform");
                o.setPlatform(true);
                return organizations.saveAndFlush(o);
            });
            rolePermissions.applyDefaultTemplates(platform.getId());
            if (users.findByEmailIgnoreCase(superAdminEmail).isEmpty()) {
                TenantContext.runAs(platform.getId(), null, () -> {
                    User u = new User();
                    u.setEmail(superAdminEmail.toLowerCase());
                    u.setFullName("Platform Super Administrator");
                    u.setRole(Role.SUPER_ADMIN);
                    String pw = superAdminPassword;
                    if (pw == null || pw.isBlank()) {
                        if (props.demo().enabled()) {
                            pw = DEMO_PASSWORD;
                        } else {
                            pw = TokenHasher.newToken();
                            log.warn("Super administrator {} created with a random password. Set CAREFLOW_SUPERADMIN_PASSWORD "
                                    + "or use account recovery to sign in.", superAdminEmail);
                        }
                    }
                    u.setPasswordHash(passwordEncoder.encode(pw));
                    users.save(u);
                });
            }
        });
    }
}
