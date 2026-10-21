package com.focus.ezviz.repository;

import com.focus.ezviz.domain.DownloadRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DownloadRuleRepository extends JpaRepository<DownloadRule, Long> {
    List<DownloadRule> findAllByOrderByIdAsc();

    List<DownloadRule> findByEnabledTrueOrderByIdAsc();

    List<DownloadRule> findByCameraIdOrderByIdAsc(Long cameraId);
}
