package com.ainotes.ingest;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndexJobRepository extends JpaRepository<IndexJob, UUID> {

    List<IndexJob> findTop50ByStatusOrderByCreatedAtAsc(String status);
}
