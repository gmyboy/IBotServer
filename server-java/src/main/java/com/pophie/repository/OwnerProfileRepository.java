package com.pophie.repository;

import com.pophie.entity.OwnerProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OwnerProfileRepository extends JpaRepository<OwnerProfileEntity, String> {

    @Modifying
    @Transactional
    @Query("DELETE FROM OwnerProfileEntity o WHERE o.robotId = :robotId")
    int deleteByRobotId(@Param("robotId") String robotId);
}
