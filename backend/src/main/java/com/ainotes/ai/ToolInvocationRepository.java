package com.ainotes.ai;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, UUID> {

    /** 运行轨迹:一次运行的全部调用,按发生顺序。 */
    List<ToolInvocation> findByRunIdOrderByOrdAsc(UUID runId);

    /** 待确认的调用(正常一次运行最多一批)。 */
    List<ToolInvocation> findByRunIdAndStatusOrderByOrdAsc(UUID runId, String status);

    List<ToolInvocation> findByRunIdInOrderByOrdAsc(Collection<UUID> runIds);
}
