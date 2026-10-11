package com.careflow.hms.user;

import com.careflow.hms.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    Optional<User> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<User> findByOrganizationIdAndRoleAndStatus(UUID organizationId, Role role, User.Status status);

    List<User> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    @Query("""
            select u from User u
            where u.organizationId = :orgId
              and (:role is null or u.role = :role)
              and (:status is null or u.status = :status)
              and (:q is null or lower(u.fullName) like :q or lower(u.email) like :q)
            """)
    Page<User> search(@Param("orgId") UUID orgId, @Param("role") Role role, @Param("status") User.Status status,
                      @Param("q") String q, Pageable pageable);

    /** Staff accounts only (never patients or platform super administrators), so paging totals are accurate. */
    @Query("""
            select u from User u
            where u.organizationId = :orgId
              and u.role not in (com.careflow.hms.security.Role.PATIENT, com.careflow.hms.security.Role.SUPER_ADMIN)
              and (:role is null or u.role = :role)
              and (:status is null or u.status = :status)
              and (:q is null or lower(u.fullName) like :q or lower(u.email) like :q)
            """)
    Page<User> searchStaff(@Param("orgId") UUID orgId, @Param("role") Role role, @Param("status") User.Status status,
                           @Param("q") String q, Pageable pageable);

    long countByOrganizationIdAndRole(UUID organizationId, Role role);
}
