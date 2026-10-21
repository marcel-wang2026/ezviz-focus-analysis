package com.focus.ezviz.web.api;

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
import com.focus.ezviz.service.AnalysisService;
import com.focus.ezviz.service.DownloadExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外 REST 接口：便于多个 Python 算法/外部系统读取视频、提交分析、获取报告。
 * <p>鉴权方式：与 Web 相同的登录会话（Cookie）。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CameraRepository cameraRepository;
    private final VideoRecordRepository videoRepository;
    private final AnalysisTaskRepository taskRepository;
    private final AnalysisReportRepository reportRepository;
    private final AnalysisService analysisService;
    private final DownloadExecutor downloadExecutor;
    private final ObjectMapper objectMapper;

    public ApiController(CameraRepository cameraRepository, VideoRecordRepository videoRepository,
                         AnalysisTaskRepository taskRepository, AnalysisReportRepository reportRepository,
                         AnalysisService analysisService, DownloadExecutor downloadExecutor,
                         ObjectMapper objectMapper) {
        this.cameraRepository = cameraRepository;
        this.videoRepository = videoRepository;
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.analysisService = analysisService;
        this.downloadExecutor = downloadExecutor;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "time", LocalDateTime.now().format(FORMATTER));
    }

    @GetMapping("/cameras")
    public List<Camera> cameras() {
        return cameraRepository.findAllByOrderByIdAsc();
    }

    @GetMapping("/videos")
    public List<VideoRecord> videos(@RequestParam(required = false) Long cameraId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "20") int limit) {
        List<VideoRecord> videos = cameraId == null
                ? videoRepository.findAllByOrderByBeginTimeDesc(PageRequest.of(0, limit))
                : videoRepository.findByCameraIdOrderByBeginTimeDesc(cameraId, PageRequest.of(0, limit)).getContent();
        if (status == null || status.isBlank()) {
            return videos;
        }
        return videos.stream().filter(v -> status.equalsIgnoreCase(v.getStatus())).toList();
    }

    @GetMapping("/videos/{id}")
    public ResponseEntity<VideoRecord> video(@PathVariable Long id) {
        return videoRepository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/videos/{id}/analyze")
    public Map<String, Object> analyze(@PathVariable Long id,
                                       @RequestParam(defaultValue = "focus,distraction,hands,phone,audio") String tasks) {
        AnalysisTask task = analysisService.submit(id, tasks, "MANUAL");
        return Map.of("taskId", task.getId(), "status", task.getStatus());
    }

    @GetMapping("/tasks")
    public List<AnalysisTask> tasks(@RequestParam(required = false) Long videoId) {
        return videoId == null ? taskRepository.findAll() : taskRepository.findByVideoIdOrderByIdDesc(videoId);
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<AnalysisTask> task(@PathVariable Long id) {
        return taskRepository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/reports")
    public List<AnalysisReport> reports(@RequestParam(required = false) Long cameraId,
                                        @RequestParam(defaultValue = "20") int limit) {
        List<AnalysisReport> all = reportRepository.findAllByOrderByIdDesc();
        return all.stream()
                .filter(r -> cameraId == null || cameraId.equals(r.getCameraId()))
                .limit(limit)
                .toList();
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<AnalysisReport> report(@PathVariable Long id) {
        return reportRepository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** 只取报告中的原始算法 JSON，方便其他算法二次消费 */
    @GetMapping("/reports/{id}/raw")
    public ResponseEntity<JsonNode> rawReport(@PathVariable Long id) throws Exception {
        AnalysisReport report = reportRepository.findById(id).orElse(null);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }
        String raw = report.getRawJson() == null ? "{}" : report.getRawJson();
        return ResponseEntity.ok(objectMapper.readTree(raw));
    }

    public record PointAnalysisRequest(Long cameraId, String from, String to, String tasks, Boolean autoAnalyze) {
    }

    /** 指定摄像头与时间点，下载并分析 */
    @PostMapping("/analysis/point")
    public Map<String, Object> analyzePoint(@RequestBody PointAnalysisRequest request) {
        if (request.cameraId() == null || request.from() == null || request.to() == null) {
            throw new IllegalArgumentException("cameraId / from / to 均为必填");
        }
        LocalDateTime from = LocalDateTime.parse(request.from().replace(' ', 'T'));
        LocalDateTime to = LocalDateTime.parse(request.to().replace(' ', 'T'));
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }
        boolean autoAnalyze = request.autoAnalyze() == null || request.autoAnalyze();
        downloadExecutor.runManual(request.cameraId(), from, to, autoAnalyze,
                request.tasks() == null ? "focus,distraction,hands,phone,audio" : request.tasks());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submitted", true);
        result.put("cameraId", request.cameraId());
        result.put("from", from.format(FORMATTER));
        result.put("to", to.format(FORMATTER));
        result.put("autoAnalyze", autoAnalyze);
        return result;
    }
}
