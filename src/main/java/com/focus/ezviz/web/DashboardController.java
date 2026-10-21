package com.focus.ezviz.web;

import com.focus.ezviz.domain.AnalysisReport;
import com.focus.ezviz.domain.AnalysisTask;
import com.focus.ezviz.domain.VideoRecord;
import com.focus.ezviz.repository.AnalysisReportRepository;
import com.focus.ezviz.repository.AnalysisTaskRepository;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.VideoRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class DashboardController {

    private final CameraRepository cameraRepository;
    private final VideoRecordRepository videoRepository;
    private final AnalysisTaskRepository taskRepository;
    private final AnalysisReportRepository reportRepository;

    public DashboardController(CameraRepository cameraRepository,
                               VideoRecordRepository videoRepository,
                               AnalysisTaskRepository taskRepository,
                               AnalysisReportRepository reportRepository) {
        this.cameraRepository = cameraRepository;
        this.videoRepository = videoRepository;
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<VideoRecord> recentVideos = videoRepository
                .findAllByOrderByBeginTimeDesc(PageRequest.of(0, 6));
        List<AnalysisReport> recentReports = reportRepository
                .findAllByOrderByIdDesc(PageRequest.of(0, 6)).getContent();
        List<AnalysisTask> runningTasks = taskRepository.findByStatusOrderByIdAsc("RUNNING");

        model.addAttribute("cameraCount", cameraRepository.count());
        model.addAttribute("videoCount", videoRepository.count());
        model.addAttribute("readyVideoCount", videoRepository.countByStatus("READY"));
        model.addAttribute("reportCount", reportRepository.count());
        model.addAttribute("runningTaskCount", runningTasks.size());
        model.addAttribute("recentVideos", recentVideos);
        model.addAttribute("recentReports", recentReports);
        model.addAttribute("runningTasks", runningTasks);
        return "dashboard";
    }
}
