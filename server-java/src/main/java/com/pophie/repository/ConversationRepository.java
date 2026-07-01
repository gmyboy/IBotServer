package com.pophie.repository;

import com.pophie.entity.ConversationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {

    /** 近期对话：按 id DESC 取 n 条（调用方再 reverse）。 */
    List<ConversationEntity> findByRobotIdAndSessionIdOrderByIdDesc(String robotId, String sessionId, Pageable pageable);

    List<ConversationEntity> findByUserIdAndSessionIdOrderByIdDesc(String userId, String sessionId, Pageable pageable);

    @Query("SELECT c FROM ConversationEntity c WHERE c.robotId = :robotId "
            + "AND (:sessionId IS NULL OR c.sessionId = :sessionId) "
            + "ORDER BY c.id DESC")
    List<ConversationEntity> listConversations(@Param("robotId") String robotId,
                                               @Param("sessionId") String sessionId,
                                               Pageable pageable);

    @Query("SELECT c FROM ConversationEntity c WHERE c.userId = :userId "
            + "AND (:sessionId IS NULL OR c.sessionId = :sessionId) "
            + "ORDER BY c.id DESC")
    List<ConversationEntity> listConversationsByUser(@Param("userId") String userId,
                                                     @Param("sessionId") String sessionId,
                                                     Pageable pageable);

    @Query("SELECT c FROM ConversationEntity c WHERE c.robotId = :robotId "
            + "AND c.role = 'proactive' AND c.id > :sinceId "
            + "AND (:sessionId IS NULL OR c.sessionId = :sessionId) "
            + "ORDER BY c.id ASC")
    List<ConversationEntity> proactiveMessages(@Param("robotId") String robotId,
                                               @Param("sinceId") long sinceId,
                                               @Param("sessionId") String sessionId,
                                               Pageable pageable);

    long countByRobotId(String robotId);

    @Query("SELECT COUNT(DISTINCT c.sessionId) FROM ConversationEntity c WHERE c.robotId = :robotId")
    long countDistinctSessions(@Param("robotId") String robotId);

    @Query("SELECT DISTINCT c.robotId FROM ConversationEntity c")
    List<String> distinctRobotIds();

    @Modifying
    @Transactional
    @Query("DELETE FROM ConversationEntity c WHERE c.robotId = :robotId")
    int deleteByRobotId(@Param("robotId") String robotId);
}
