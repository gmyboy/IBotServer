package com.pophie.repository;

import com.pophie.entity.ProactiveLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ProactiveLogRepository extends JpaRepository<ProactiveLogEntity, Long> {

    List<ProactiveLogEntity> findByRobotIdOrderByIdDesc(String robotId, Pageable pageable);

    long countByRobotId(String robotId);

    @Query("SELECT DISTINCT p.robotId FROM ProactiveLogEntity p")
    List<String> distinctRobotIds();

    @Modifying
    @Transactional
    @Query("DELETE FROM ProactiveLogEntity p WHERE p.robotId = :robotId")
    int deleteByRobotId(@Param("robotId") String robotId);
}
