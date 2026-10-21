package com.focus.ezviz.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.focus.ezviz.domain.AnalysisReport;
import com.focus.ezviz.domain.AnalysisTask;
import com.focus.ezviz.domain.Camera;
import com.focus.ezviz.domain.VideoRecord;
import com.focus.ezviz.repository.AnalysisReportRepository;
import com.focus.ezviz.repository.AnalysisTaskRepository;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.VideoRecordRepository;
import com.focus.ezviz.service.ReportService;
import com.focus.ezviz.service.VideoArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/** 异步执行分析任务，并落库报告 */
@Component
public class AnalysisExecutor {

    private static final Logger log = LoggerFactory.getLogger(AnalysisExecutor.class);

    private final AnalysisTaskRepository taskRepository;
    private final AnalysisReportRepository reportRepository;
    private final VideoRecordRepository videoRepository;
    private final CameraRepository cameraRepository;
    private final AnalyzerRunner analyzerRunner;
    private final ReportService reportService;
    private final VideoArchiveService archiveService;
    private final ObjectMapper objectMapper;

    public AnalysisExecutor(AnalysisTaskRepository taskRepository,
                            AnalysisReportRepository reportRepository,
                            VideoRecordRepository videoRepository,
                            CameraRepository cameraRepository,
                            AnalyzerRunner analyzerRunner,
                            ReportService reportService,
                            VideoArchiveService archiveService,
                            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.videoRepository = videoRepository;
        this.cameraRepository = cameraRepository;
        this.analyzerRunner = analyzerRunner;
        this.reportService = reportService;
        this.archiveService = archiveService;
        this.objectMapper = objectMapper;
    }

    @Async("analysisTaskPool")
    public void run(Long taskId) {
        AnalysisTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !"PENDING".equals(task.getStatus())) {
            return;
        }
        VideoRecord video = videoRepository.findById(task.getVideoId()).orElse(null);
        if (video == null) {
            fail(task, "视频记录不存在");
            return;
        }
        Camera camera = cameraRepository.findById(video.getCameraId()).orElse(null);

        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        task.setProgress(10);
        task.setMessage("正在调用算法分析视频");
        taskRepository.save(task);

        try {
            JsonNode root = analyzerRunner.analyze(video.getFilePath(),
                    camera == null ? null : camera.getRoi(),
                    task.getTasks());
            task.setProgress(70);
            task.setMessage("算法完成，正在生成报告");
            taskRepository.save(task);

            Path jsonPath = archiveService.reportJsonPath(video);
            Files.createDirectories(jsonPath.getParent());
            Files.writeString(jsonPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);

            AnalysisReport report = reportService.build(video, root, jsonPath.toString());
            report = reportRepository.save(report);

            task.setReportId(report.getId());
            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage("分析完成");
            task.setFinishedAt(LocalDateTime.now());
            taskRepository.save(task);
            log.info("视频 {} 分析完成，报告 ID={}", video.getId(), report.getId());
        } catch (Exception e) {
            log.error("视频 {} 分析失败", video.getId(), e);
            fail(task, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void fail(AnalysisTask task, String message) {
        task.setStatus("FAILED");
        task.setProgress(0);
        task.setMessage(truncate(message, 1000));
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
