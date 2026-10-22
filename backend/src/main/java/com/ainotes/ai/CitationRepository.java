package com.ainotes.ai;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface CitationRepository extends JpaRepository<Citation, UUID> {

    /** 按回答里的编号排序,不能按主键(随机 UUID)排。 */
    List<Citation> findByMessageIdInOrderByOrdAsc(Collection<UUID> messageIds);

    /** 单条回答的引用:续跑时要把它装回运行的引用编号里(见 AgentService.load)。 */
    List<Citation> findByMessageIdOrderByOrdAsc(UUID messageId);

    /** 收尾时"先删后写":暂停一次写一遍、续跑结束再写一遍,不能留下上一次的半套引用。 */
    @Transactional
    void deleteByMessageId(UUID messageId);
}
