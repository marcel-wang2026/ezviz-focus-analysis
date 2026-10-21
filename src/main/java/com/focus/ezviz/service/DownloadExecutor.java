package com.focus.ezviz.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 下载任务的异步入口（避免在 Web 请求线程中执行耗时下载） */
@Component
public class DownloadExecutor {

    private final VideoDownloadService downloadService;

    public DownloadExecutor(VideoDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @Async("downloadTaskPool")
    public void runRule(Long ruleId) {
        downloadService.executeRule(ruleId);
    }

    @Async("downloadTaskPool")
    public void runManual(Long cameraId, LocalDateTime from, LocalDateTime to,
                          boolean autoAnalyze, String tasks) {
        downloadService.executeManual(cameraId, from, to, autoAnalyze, tasks);
    }
}
