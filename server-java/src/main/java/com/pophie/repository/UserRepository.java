package com.pophie.repository;

import com.pophie.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    List<UserEntity> findByNicknameContaining(String nickname);
}
