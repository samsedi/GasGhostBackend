package com.relayer.gasghosts.service;

import com.relayer.gasghosts.model.RelayTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RelayTaskRepository extends JpaRepository<RelayTask, Long> {
    List<RelayTask> findByStatusAndCreatedAtBefore(RelayTask.Status status, LocalDateTime time);
    RelayTask findByTxHash(String txHash);
}
