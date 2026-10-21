package com.focus.ezviz.web;

import com.focus.ezviz.domain.AnalysisTask;
import com.focus.ezviz.domain.Camera;
import com.focus.ezviz.domain.VideoRecord;
import com.focus.ezviz.repository.AnalysisTaskRepository;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.VideoRecordRepository;
import com.focus.ezviz.service.AnalysisService;
import com.focus.ezviz.service.CameraService;
import com.focus.ezviz.service.DownloadExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

@Controller
public class AnalysisController {

    private final CameraService cameraService;
    private final CameraRepository cameraRepository;
    private final VideoRecordRepository videoRepository;
    private final AnalysisTaskRepository taskRepository;
    private final AnalysisService analysisService;
    private final DownloadExecutor downloadExecutor;

    public AnalysisController(CameraService cameraService, CameraRepository cameraRepository,
                              VideoRecordRepository videoRepository, AnalysisTaskRepository taskRepository,
                              AnalysisService analysisService, DownloadExecutor downloadExecutor) {
        this.cameraService = cameraService;
        this.cameraRepository = cameraRepository;
        this.videoRepository = videoRepository;
        this.taskRepository = taskRepository;
        this.analysisService = analysisService;
        this.downloadExecutor = downloadExecutor;
    }

    /** 分析中心：按时间点下载并分析 + 近期任务 */
    @GetMapping("/analysis")
    public String analysis(Model model) {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        model.addAttribute("cameras", cameraService.findAll());
        model.addAttribute("defaultFrom", now.minusHours(2));
        model.addAttribute("defaultTo", now);
        Page<AnalysisTask> tasks = taskRepository.findAllByOrderByIdDesc(PageRequest.of(0, 15));
        model.addAttribute("tasks", tasks.getContent());
        return "analysis";
    }

    /** 按指定时间点/时间段分析：先下载归档，下载完成后自动提交算法 */
    @PostMapping("/analysis/point")
    public String analyzePoint(@RequestParam Long cameraId,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                               @RequestParam(defaultValue = "focus,distraction,hands,phone,audio") String tasks,
                               @RequestParam(defaultValue = "true") boolean autoAnalyze,
                               RedirectAttributes redirect) {
        Camera camera = cameraRepository.findById(cameraId).orElse(null);
        if (camera == null) {
            redirect.addFlashAttribute("error", "摄像头不存在");
            return "redirect:/analysis";
        }
        if (!to.isAfter(from)) {
            redirect.addFlashAttribute("error", "结束时间必须晚于开始时间");
            return "redirect:/analysis";
        }
        downloadExecutor.runManual(cameraId, from, to, autoAnalyze, tasks);
        redirect.addFlashAttribute("message",
                "已提交下载任务：" + camera.getName() + " " + from + " ~ " + to
                        + (autoAnalyze ? "，下载完成后自动分析" : ""));
        return "redirect:/analysis";
    }

    /** 对已归档视频提交分析 */
    @PostMapping("/videos/{videoId}/analyze")
    public String analyzeVideo(@PathVariable Long videoId,
                               @RequestParam(defaultValue = "focus,distraction,hands,phone,audio") String tasks,
                               RedirectAttributes redirect) {
        try {
            AnalysisTask task = analysisService.submit(videoId, tasks, "MANUAL");
            redirect.addFlashAttribute("message", "分析任务已提交（任务 #" + task.getId() + "）");
            return "redirect:/tasks/" + task.getId();
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "提交分析失败：" + e.getMessage());
            VideoRecord video = videoRepository.findById(videoId).orElse(null);
            return video != null ? "redirect:/videos/" + videoId : "redirect:/videos";
        }
    }

    @GetMapping("/tasks")
    public String tasks(@RequestParam(defaultValue = "0") int page, Model model) {
        Page<AnalysisTask> result = taskRepository.findAllByOrderByIdDesc(PageRequest.of(Math.max(page, 0), 20));
        model.addAttribute("page", result);
        model.addAttribute("videos", videoRepository.findAll());
        return "tasks";
    }

    @GetMapping("/tasks/{id}")
    public String taskDetail(@PathVariable Long id, Model model) {
        AnalysisTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
        VideoRecord video = videoRepository.findById(task.getVideoId()).orElse(null);
        model.addAttribute("task", task);
        model.addAttribute("video", video);
        model.addAttribute("report", task.getReportId() == null ? null
                : analysisService.latestReport(task.getVideoId()).orElse(null));
        return "task-detail";
    }

    @PostMapping("/tasks/{id}/retry")
    public String retry(@PathVariable Long id, RedirectAttributes redirect) {
        AnalysisTask task = taskRepository.findById(id).orElse(null);
        if (task == null) {
            redirect.addFlashAttribute("error", "任务不存在");
            return "redirect:/tasks";
        }
        try {
            AnalysisTask retry = analysisService.submit(task.getVideoId(), task.getTasks(), "MANUAL");
            return "redirect:/tasks/" + retry.getId();
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "重试失败：" + e.getMessage());
            return "redirect:/tasks/" + id;
        }
    }
}
