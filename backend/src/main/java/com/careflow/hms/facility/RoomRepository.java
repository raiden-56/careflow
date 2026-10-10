package com.careflow.hms.facility;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Optional<Room> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Room> findByOrganizationIdAndWardIdOrderByRoomNumber(UUID organizationId, UUID wardId);

    List<Room> findByOrganizationId(UUID organizationId);

    List<Room> findByIdIn(Collection<UUID> ids);

    boolean existsByWardIdAndRoomNumber(UUID wardId, String roomNumber);
}
