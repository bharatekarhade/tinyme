package com.tinyme.auth.repository;

import com.tinyme.auth.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface AccountJpaRepository extends JpaRepository<AccountEntity, Short> {
    Optional<AccountEntity> findByEmail(String email);
}
