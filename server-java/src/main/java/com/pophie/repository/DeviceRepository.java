package com.pophie.repository;

import com.pophie.entity.DeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<DeviceEntity, String> {

    List<DeviceEntity> findByUserIdOrderByBoundAtAsc(String userId);

    Optional<DeviceEntity> findByRobotId(String robotId);
}
