package com.tinyme.agent.repository;

import com.tinyme.agent.entity.SettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentSettingsJpaRepository extends JpaRepository<SettingEntity, String> {
}
