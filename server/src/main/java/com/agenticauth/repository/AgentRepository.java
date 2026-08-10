package com.agenticauth.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.agenticauth.domain.Agent;

public interface AgentRepository extends JpaRepository<Agent, String> {

}
