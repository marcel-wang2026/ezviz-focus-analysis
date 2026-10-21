package com.focus.ezviz.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.focus.ezviz.domain.AnalysisReport;
import com.focus.ezviz.domain.VideoRecord;
import com.focus.ezviz.report.BreakDto;
import com.focus.ezviz.report.EventDto;
import com.focus.ezviz.report.StudySegmentDto;
import com.focus.ezviz.repository.AnalysisReportRepository;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.VideoRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Controller
@RequestMapping("/reports")
public class ReportController {

    private final AnalysisReportRepository reportRepository;
    private final VideoRecordRepository videoRepository;
    private final CameraRepository cameraRepository;
    private final ObjectMapper objectMapper;

    public ReportController(AnalysisReportRepository reportRepository,
                            VideoRecordRepository videoRepository,
                            CameraRepository cameraRepository,
                            ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.videoRepository = videoRepository;
        this.cameraRepository = cameraRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Long cameraId,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), 20);
        Page<AnalysisReport> result = cameraId == null
                ? reportRepository.findAllByOrderByIdDesc(pageable)
                : reportRepository.findByCameraIdOrderByIdDesc(cameraId, pageable);
        model.addAttribute("page", result);
        model.addAttribute("cameras", cameraRepository.findAllByOrderByIdAsc());
        model.addAttribute("cameraId", cameraId);
        return "reports";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        AnalysisReport report = reportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报告不存在: " + id));
        VideoRecord video = videoRepository.findById(report.getVideoId()).orElse(null);
        model.addAttribute("report", report);
        model.addAttribute("video", video);
        model.addAttribute("segments", parseList(report.getStudySegmentsJson(), new TypeReference<List<StudySegmentDto>>() {
        }));
        model.addAttribute("breaks", parseList(report.getBreaksJson(), new TypeReference<List<BreakDto>>() {
        }));
        model.addAttribute("events", parseList(report.getEventsJson(), new TypeReference<List<EventDto>>() {
        }));
        model.addAttribute("algorithms", parseTree(report.getAlgorithmInfo()));
        return "report-detail";
    }

    /** 下载原始分析 JSON */
    @GetMapping("/{id}/json")
    public ResponseEntity<byte[]> downloadJson(@PathVariable Long id) {
        AnalysisReport report = reportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报告不存在: " + id));
        byte[] body = (report.getRawJson() == null ? "{}" : report.getRawJson())
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-" + id + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return List.of();
        }
    }

    private JsonNode parseTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }
}
