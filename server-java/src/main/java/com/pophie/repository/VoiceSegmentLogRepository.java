package com.pophie.repository;

import com.pophie.entity.VoiceSegmentLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface VoiceSegmentLogRepository extends JpaRepository<VoiceSegmentLogEntity, Long> {

    List<VoiceSegmentLogEntity> findByRobotIdOrderByIdDesc(String robotId, Pageable pageable);

    @Query("""
            SELECT v FROM VoiceSegmentLogEntity v
            WHERE v.robotId = :robotId
              AND (:userId IS NULL OR v.userId = :userId)
              AND (:sessionId IS NULL OR v.sessionId = :sessionId)
              AND (:owner IS NULL OR v.owner = :owner)
            ORDER BY v.id DESC
            """)
    List<VoiceSegmentLogEntity> search(
            @Param("robotId") String robotId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("owner") Boolean owner,
            Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM VoiceSegmentLogEntity v WHERE v.robotId = :robotId")
    int deleteByRobotId(@Param("robotId") String robotId);
}
