package com.focus.ezviz.service;

import com.focus.ezviz.domain.Camera;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.DownloadRuleRepository;
import com.focus.ezviz.repository.VideoRecordRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** 摄像头配置管理 */
@Service
public class CameraService {

    private final CameraRepository cameraRepository;
    private final VideoRecordRepository videoRepository;
    private final DownloadRuleRepository ruleRepository;

    public CameraService(CameraRepository cameraRepository,
                         VideoRecordRepository videoRepository,
                         DownloadRuleRepository ruleRepository) {
        this.cameraRepository = cameraRepository;
        this.videoRepository = videoRepository;
        this.ruleRepository = ruleRepository;
    }

    public List<Camera> findAll() {
        return cameraRepository.findAllByOrderByIdAsc();
    }

    public Camera get(Long id) {
        return cameraRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("摄像头不存在: " + id));
    }

    public Camera save(Camera camera) {
        validateRoi(camera.getRoi());
        if (camera.getName() == null || camera.getName().isBlank()) {
            camera.setName(camera.getDeviceSerial());
        }
        return cameraRepository.save(camera);
    }

    public void delete(Long id) {
        long videos = videoRepository.search(id, null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements();
        if (videos > 0) {
            throw new IllegalStateException("该摄像头已有 " + videos + " 条视频记录，不能删除");
        }
        long rules = ruleRepository.findByCameraIdOrderByIdAsc(id).size();
        if (rules > 0) {
            throw new IllegalStateException("该摄像头还有 " + rules + " 条下载规则，请先删除规则");
        }
        cameraRepository.deleteById(id);
    }

    private void validateRoi(String roi) {
        if (roi == null || roi.isBlank()) {
            return;
        }
        String[] parts = roi.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("ROI 格式应为 x,y,w,h（归一化 0-1），例如 0.2,0.1,0.6,0.8");
        }
        try {
            for (String part : parts) {
                double value = Double.parseDouble(part.trim());
                if (value < 0 || value > 1) {
                    throw new IllegalArgumentException("ROI 数值必须在 0-1 之间");
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ROI 必须是 4 个 0-1 之间的数字");
        }
    }
}
