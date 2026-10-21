package com.focus.ezviz.repository;

import com.focus.ezviz.domain.AnalysisTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {

    List<AnalysisTask> findByStatusOrderByIdAsc(String status);

    List<AnalysisTask> findByVideoIdOrderByIdDesc(Long videoId);

    Optional<AnalysisTask> findFirstByVideoIdAndStatusOrderByIdDesc(Long videoId, String status);

    Page<AnalysisTask> findAllByOrderByIdDesc(Pageable pageable);

    long countByStatus(String status);
}
