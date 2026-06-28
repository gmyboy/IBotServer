package com.pophie.repository;

import com.pophie.entity.MemoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MemoryRepository extends JpaRepository<MemoryEntity, Long> {

    @Query("SELECT m FROM MemoryEntity m WHERE m.robotId = :robotId "
            + "AND (:layer IS NULL OR m.layer = :layer) "
            + "AND (:sessionId IS NULL OR m.sessionId = :sessionId) "
            + "ORDER BY m.id DESC")
    List<MemoryEntity> listMemories(@Param("robotId") String robotId,
                                    @Param("layer") String layer,
                                    @Param("sessionId") String sessionId,
                                    Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM MemoryEntity m WHERE m.id = :id AND m.robotId = :robotId")
    int deleteByIdAndRobotId(@Param("id") Long id, @Param("robotId") String robotId);

    long countByRobotId(String robotId);

    @Query("SELECT m.layer, COUNT(m) FROM MemoryEntity m WHERE m.robotId = :robotId GROUP BY m.layer")
    List<Object[]> countByLayer(@Param("robotId") String robotId);

    @Query("SELECT DISTINCT m.robotId FROM MemoryEntity m")
    List<String> distinctRobotIds();

    @Modifying
    @Transactional
    @Query("DELETE FROM MemoryEntity m WHERE m.robotId = :robotId")
    int deleteByRobotId(@Param("robotId") String robotId);
}
