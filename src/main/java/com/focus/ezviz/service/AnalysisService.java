package com.focus.ezviz.service;

import com.focus.ezviz.analysis.AnalysisExecutor;
import com.focus.ezviz.domain.AnalysisReport;
import com.focus.ezviz.domain.AnalysisTask;
import com.focus.ezviz.domain.Camera;
import com.focus.ezviz.domain.VideoRecord;
import com.focus.ezviz.repository.AnalysisReportRepository;
import com.focus.ezviz.repository.AnalysisTaskRepository;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.VideoRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 分析任务编排：创建任务、异步执行、查询结果 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final AnalysisTaskRepository taskRepository;
    private final AnalysisReportRepository reportRepository;
    private final VideoRecordRepository videoRepository;
    private final CameraRepository cameraRepository;
    private final AnalysisExecutor analysisExecutor;

    public AnalysisService(AnalysisTaskRepository taskRepository,
                           AnalysisReportRepository reportRepository,
                           VideoRecordRepository videoRepository,
                           CameraRepository cameraRepository,
                           AnalysisExecutor analysisExecutor) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.videoRepository = videoRepository;
        this.cameraRepository = cameraRepository;
        this.analysisExecutor = analysisExecutor;
    }

    /**
     * 为指定视频创建并提交分析任务。
     */
    public AnalysisTask submit(Long videoId, String tasks, String triggerType) {
        VideoRecord video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("视频不存在: " + videoId));
        if (!"READY".equals(video.getStatus())) {
            throw new IllegalStateException("视频尚未下载完成，无法分析");
        }
        Camera camera = cameraRepository.findById(video.getCameraId()).orElse(null);
        if (camera != null && !camera.isAnalysisEnabled()) {
            throw new IllegalStateException("摄像头未开启分析开关");
        }

        AnalysisTask task = new AnalysisTask();
        task.setVideoId(videoId);
        task.setCameraId(video.getCameraId());
        task.setTasks(tasks == null || tasks.isBlank() ? "focus,distraction,hands,phone,audio" : tasks);
        task.setStatus("PENDING");
        task.setTriggerType(triggerType == null ? "MANUAL" : triggerType);
        task.setProgress(0);
        task.setMessage("已排队");
        task = taskRepository.save(task);
        analysisExecutor.run(task.getId());
        return task;
    }

    /** 自动分析判重：同一视频已有进行中或成功的任务则跳过 */
    public Optional<AnalysisTask> findExisting(Long videoId) {
        Optional<AnalysisTask> running = taskRepository.findFirstByVideoIdAndStatusOrderByIdDesc(videoId, "RUNNING");
        if (running.isPresent()) {
            return running;
        }
        return taskRepository.findFirstByVideoIdAndStatusOrderByIdDesc(videoId, "SUCCESS");
    }

    public Optional<AnalysisReport> latestReport(Long videoId) {
        return reportRepository.findFirstByVideoIdOrderByIdDesc(videoId);
    }

    public List<AnalysisTask> recentTasks() {
        return taskRepository.findAll();
    }

    /** 应用重启后把中断的任务标记为失败，避免界面一直显示运行中 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        List<AnalysisTask> running = taskRepository.findByStatusOrderByIdAsc("RUNNING");
        for (AnalysisTask task : running) {
            task.setStatus("FAILED");
            task.setMessage("服务重启导致分析中断，请重新提交");
            task.setFinishedAt(LocalDateTime.now());
            taskRepository.save(task);
        }
        if (!running.isEmpty()) {
            log.warn("已将 {} 个中断的分析任务标记为失败", running.size());
        }
    }
}
