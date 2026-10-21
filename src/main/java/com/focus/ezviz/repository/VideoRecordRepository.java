package com.focus.ezviz.repository;

import com.focus.ezviz.domain.VideoRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VideoRecordRepository extends JpaRepository<VideoRecord, Long> {

    boolean existsByCameraIdAndBeginTimeAndEndTime(Long cameraId, LocalDateTime beginTime, LocalDateTime endTime);

    Optional<VideoRecord> findFirstByCameraIdAndBeginTimeAndEndTime(Long cameraId, LocalDateTime beginTime, LocalDateTime endTime);

    List<VideoRecord> findByCameraIdAndBeginTimeBetweenOrderByBeginTimeAsc(Long cameraId, LocalDateTime from, LocalDateTime to);

    List<VideoRecord> findByStatusOrderByBeginTimeDesc(String status);

    List<VideoRecord> findAllByOrderByBeginTimeDesc(Pageable pageable);

    List<VideoRecord> findByStatusAndBeginTimeBefore(String status, LocalDateTime before);

    Page<VideoRecord> findByCameraIdOrderByBeginTimeDesc(Long cameraId, Pageable pageable);

    @Query("""
            select v from VideoRecord v
            where (:cameraId is null or v.cameraId = :cameraId)
              and (:status is null or v.status = :status)
              and (:from is null or v.beginTime >= :from)
              and (:to is null or v.beginTime <= :to)
            order by v.beginTime desc
            """)
    Page<VideoRecord> search(@Param("cameraId") Long cameraId,
                             @Param("status") String status,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to,
                             Pageable pageable);

    long countByStatus(String status);
}
