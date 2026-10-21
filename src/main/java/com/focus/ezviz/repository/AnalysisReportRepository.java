package com.focus.ezviz.repository;

import com.focus.ezviz.domain.AnalysisReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    Optional<AnalysisReport> findFirstByVideoIdOrderByIdDesc(Long videoId);

    List<AnalysisReport> findAllByOrderByIdDesc();

    Page<AnalysisReport> findAllByOrderByIdDesc(Pageable pageable);

    Page<AnalysisReport> findByCameraIdOrderByIdDesc(Long cameraId, Pageable pageable);

    long countByCameraId(Long cameraId);
}
