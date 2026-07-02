package com.pophie.repository;

import com.pophie.entity.RobotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RobotRepository extends JpaRepository<RobotEntity, String> {

    @Query("SELECT r.robotId FROM RobotEntity r")
    List<String> allRobotIds();

    List<RobotEntity> findByDisplayNameContaining(String displayName);
}
