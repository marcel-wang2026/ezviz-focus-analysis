package com.focus.ezviz.repository;

import com.focus.ezviz.domain.Camera;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CameraRepository extends JpaRepository<Camera, Long> {
    List<Camera> findAllByOrderByIdAsc();

    List<Camera> findByAccountIdOrderByIdAsc(Long accountId);

    List<Camera> findByEnabledTrueOrderByIdAsc();

    Optional<Camera> findByAccountIdAndDeviceSerialAndChannelNo(Long accountId, String deviceSerial, int channelNo);
}
