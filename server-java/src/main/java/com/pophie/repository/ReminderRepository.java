package com.pophie.repository;

import com.pophie.entity.ReminderEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ReminderRepository extends JpaRepository<ReminderEntity, Long> {

    @Query("SELECT r FROM ReminderEntity r WHERE r.robotId = :robotId "
            + "AND (:status IS NULL OR r.status = :status) "
            + "ORDER BY r.remindAt ASC")
    List<ReminderEntity> listReminders(@Param("robotId") String robotId,
                                       @Param("status") String status,
                                       Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE ReminderEntity r SET r.status = 'cancelled' WHERE r.id = :id AND r.status = 'pending'")
    int cancel(@Param("id") Long id);

    @Query("SELECT r FROM ReminderEntity r WHERE r.status = 'pending' AND r.remindAt <= :now ORDER BY r.remindAt ASC")
    List<ReminderEntity> dueReminders(@Param("now") String now);

    long countByRobotIdAndStatus(String robotId, String status);

    @Query("SELECT DISTINCT r.robotId FROM ReminderEntity r")
    List<String> distinctRobotIds();

    @Modifying
    @Transactional
    @Query("DELETE FROM ReminderEntity r WHERE r.robotId = :robotId")
    int deleteByRobotId(@Param("robotId") String robotId);
}
