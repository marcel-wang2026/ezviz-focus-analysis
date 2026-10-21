package com.focus.ezviz.web;

import com.focus.ezviz.domain.Camera;
import com.focus.ezviz.domain.VideoRecord;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.AnalysisReportRepository;
import com.focus.ezviz.repository.AnalysisTaskRepository;
import com.focus.ezviz.repository.VideoRecordRepository;
import com.focus.ezviz.service.VideoArchiveService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequestMapping("/videos")
public class VideoController {

    private final VideoRecordRepository videoRepository;
    private final CameraRepository cameraRepository;
    private final AnalysisReportRepository reportRepository;
    private final AnalysisTaskRepository taskRepository;
    private final VideoArchiveService archiveService;

    public VideoController(VideoRecordRepository videoRepository,
                           CameraRepository cameraRepository,
                           AnalysisReportRepository reportRepository,
                           AnalysisTaskRepository taskRepository,
                           VideoArchiveService archiveService) {
        this.videoRepository = videoRepository;
        this.cameraRepository = cameraRepository;
        this.reportRepository = reportRepository;
        this.taskRepository = taskRepository;
        this.archiveService = archiveService;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Long cameraId,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String date,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        LocalDateTime from = null;
        LocalDateTime to = null;
        if (date != null && !date.isBlank()) {
            LocalDate day = LocalDate.parse(date);
            from = day.atStartOfDay();
            to = day.plusDays(1).atStartOfDay();
        }
        Page<VideoRecord> result = videoRepository.search(cameraId,
                status == null || status.isBlank() ? null : status, from, to,
                PageRequest.of(Math.max(page, 0), 20));
        model.addAttribute("page", result);
        model.addAttribute("cameras", cameraRepository.findAllByOrderByIdAsc());
        model.addAttribute("cameraId", cameraId);
        model.addAttribute("status", status);
        model.addAttribute("date", date);
        return "videos";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        VideoRecord video = videoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("视频不存在: " + id));
        model.addAttribute("video", video);
        model.addAttribute("report", reportRepository.findFirstByVideoIdOrderByIdDesc(id).orElse(null));
        model.addAttribute("tasks", taskRepository.findByVideoIdOrderByIdDesc(id));
        return "video-detail";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        VideoRecord video = videoRepository.findById(id).orElse(null);
        if (video == null) {
            redirect.addFlashAttribute("error", "视频不存在");
            return "redirect:/videos";
        }
        archiveService.deleteVideo(video);
        if (video.getFilePath() != null) {
            archiveService.cleanupEmptyDirectories(Path.of(video.getFilePath()).getParent());
        }
        video.setStatus("DELETED");
        video.setMessage("已手动删除文件");
        videoRepository.save(video);
        redirect.addFlashAttribute("message", "视频文件已删除（记录保留）");
        return "redirect:/videos";
    }

    /** 在线播放归档视频 */
    @GetMapping("/{id}/stream")
    public ResponseEntity<Resource> stream(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean download) {
        VideoRecord video = videoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("视频不存在: " + id));
        if (video.getFilePath() == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(video.getFilePath());
        if (!path.toFile().exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(path);
        String fileName = path.getFileName().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("video/mp4"));
        if (download) {
            headers.setContentDispositionFormData("attachment", fileName);
        } else {
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        }
        return ResponseEntity.ok().headers(headers).body(resource);
    }

    /** REST：查询视频列表（供算法平台或前端调用） */
    @GetMapping("/api")
    @ResponseBody
    public List<VideoRecord> apiList(@RequestParam(required = false) Long cameraId,
                                     @RequestParam(defaultValue = "20") int size) {
        Camera camera = cameraId == null ? null : cameraRepository.findById(cameraId).orElse(null);
        if (camera != null) {
            return videoRepository.findByCameraIdOrderByBeginTimeDesc(camera.getId(),
                    PageRequest.of(0, size)).getContent();
        }
        return videoRepository.findAllByOrderByBeginTimeDesc(PageRequest.of(0, size));
    }
}
