package com.focus.ezviz.service;

import com.focus.ezviz.config.AppProperties;
import com.focus.ezviz.domain.Camera;
import com.focus.ezviz.domain.DownloadRule;
import com.focus.ezviz.domain.EzvizAccount;
import com.focus.ezviz.domain.VideoRecord;
import com.focus.ezviz.ezviz.EzvizClient;
import com.focus.ezviz.ezviz.EzvizClient.RecordingSpan;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.DownloadRuleRepository;
import com.focus.ezviz.repository.EzvizAccountRepository;
import com.focus.ezviz.repository.VideoRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 萤石录像下载与归档。
 *
 * <p>流程：查询时间段内的录像分段 → 获取 HLS 地址 → ffmpeg 转封装为 MP4 → 按摄像头/日期归档 → 可选自动分析。</p>
 */
@Service
public class VideoDownloadService {

    private static final Logger log = LoggerFactory.getLogger(VideoDownloadService.class);

    private final EzvizAccountRepository accountRepository;
    private final CameraRepository cameraRepository;
    private final DownloadRuleRepository ruleRepository;
    private final VideoRecordRepository videoRepository;
    private final EzvizClient ezvizClient;
    private final MediaTools mediaTools;
    private final VideoArchiveService archiveService;
    private final AnalysisService analysisService;
    private final AppProperties props;

    /** 防止同一条规则并发重复执行 */
    private final Set<Long> runningRules = ConcurrentHashMap.newKeySet();
    private final Set<Long> runningCameras = ConcurrentHashMap.newKeySet();

    public VideoDownloadService(EzvizAccountRepository accountRepository,
                                CameraRepository cameraRepository,
                                DownloadRuleRepository ruleRepository,
                                VideoRecordRepository videoRepository,
                                EzvizClient ezvizClient,
                                MediaTools mediaTools,
                                VideoArchiveService archiveService,
                                AnalysisService analysisService,
                                AppProperties props) {
        this.accountRepository = accountRepository;
        this.cameraRepository = cameraRepository;
        this.ruleRepository = ruleRepository;
        this.videoRepository = videoRepository;
        this.ezvizClient = ezvizClient;
        this.mediaTools = mediaTools;
        this.archiveService = archiveService;
        this.analysisService = analysisService;
        this.props = props;
    }

    public record DownloadSummary(int downloaded, int skipped, int failed, String message) {
    }

    /** 定时规则执行入口（由 DownloadExecutor 异步调用） */
    public DownloadSummary executeRule(Long ruleId) {
        if (!runningRules.add(ruleId)) {
            return new DownloadSummary(0, 0, 0, "规则正在执行中，本次跳过");
        }
        DownloadRule rule = ruleRepository.findById(ruleId).orElse(null);
        if (rule == null || !rule.isEnabled()) {
            runningRules.remove(ruleId);
            return new DownloadSummary(0, 0, 0, "规则不存在或已停用");
        }
        try {
            Camera camera = cameraRepository.findById(rule.getCameraId()).orElse(null);
            if (camera == null || !camera.isEnabled()) {
                updateRule(rule, "摄像头不存在或已停用");
                return new DownloadSummary(0, 0, 0, "摄像头不存在或已停用");
            }
            LocalDateTime now = LocalDateTime.now().withNano(0);
            LocalDateTime windowStart = now.minusMinutes(Math.max(rule.getWindowMinutes(), 1));
            LocalDateTime from = windowStart;
            if (rule.getLastRunAt() != null && rule.getLastRunAt().isAfter(windowStart)
                    && rule.getLastRunAt().isBefore(now)) {
                from = rule.getLastRunAt();
            }
            DownloadSummary summary = downloadRange(camera, from, now, rule.getSegmentMinutes(),
                    rule.isAutoAnalyze(), rule.getAnalyzeTasks(), "SCHEDULED");
            updateRule(rule, summary.message());
            return summary;
        } catch (Exception e) {
            log.error("执行下载规则 {} 失败", ruleId, e);
            updateRule(rule, "执行失败：" + e.getMessage());
            return new DownloadSummary(0, 0, 0, "执行失败：" + e.getMessage());
        } finally {
            runningRules.remove(ruleId);
        }
    }

    /** 手动触发某个摄像头的下载 */
    public DownloadSummary executeManual(Long cameraId, LocalDateTime from, LocalDateTime to,
                                         boolean autoAnalyze, String tasks) {
        if (!runningCameras.add(cameraId)) {
            return new DownloadSummary(0, 0, 0, "该摄像头正在下载，请稍后重试");
        }
        try {
            Camera camera = cameraRepository.findById(cameraId)
                    .orElseThrow(() -> new IllegalArgumentException("摄像头不存在"));
            return downloadRange(camera, from, to, 30, autoAnalyze, tasks, "MANUAL");
        } catch (Exception e) {
            log.error("手动下载摄像头 {} 失败", cameraId, e);
            return new DownloadSummary(0, 0, 0, "下载失败：" + e.getMessage());
        } finally {
            runningCameras.remove(cameraId);
        }
    }

    /**
     * 下载指定时间范围的录像。
     */
    public DownloadSummary downloadRange(Camera camera, LocalDateTime from, LocalDateTime to,
                                         int segmentMinutes, boolean autoAnalyze, String tasks,
                                         String source) {
        if (!to.isAfter(from)) {
            return new DownloadSummary(0, 0, 0, "结束时间必须晚于开始时间");
        }
        long maxMinutes = props.getEzviz().getMaxRangeMinutes();
        if (Duration.between(from, to).toMinutes() > maxMinutes) {
            to = from.plusMinutes(maxMinutes);
        }
        EzvizAccount account = accountRepository.findById(camera.getAccountId()).orElse(null);
        if (account == null) {
            return new DownloadSummary(0, 0, 0, "摄像头未绑定萤石账号");
        }

        List<RecordingSpan> spans;
        try {
            spans = resolveSpans(account, camera, from, to);
        } catch (Exception e) {
            log.warn("查询录像分段失败，尝试直接按时间范围下载: {}", e.getMessage());
            spans = List.of(new RecordingSpan(from, to));
        }
        if (spans.isEmpty()) {
            return new DownloadSummary(0, 0, 0, "该时间段没有查询到录像");
        }

        int downloaded = 0;
        int skipped = 0;
        int failed = 0;
        for (RecordingSpan span : spans) {
            LocalDateTime spanBegin = span.begin().isBefore(from) ? from : span.begin();
            LocalDateTime spanEnd = span.end().isAfter(to) ? to : span.end();
            if (!spanEnd.isAfter(spanBegin)) {
                continue;
            }
            List<RecordingSpan> pieces = ezvizClient.splitBySegment(spanBegin, spanEnd, segmentMinutes);
            for (int i = 0; i < pieces.size(); i++) {
                RecordingSpan piece = pieces.get(i);
                // 仅当整段录像正好等于一个下载片段时，才直接使用列表接口返回的播放地址
                String directUrl = pieces.size() == 1 ? span.url() : null;
                int outcome = downloadPiece(account, camera, piece, directUrl, autoAnalyze, tasks, source);
                if (outcome > 0) {
                    downloaded++;
                } else if (outcome == 0) {
                    skipped++;
                } else {
                    failed++;
                }
            }
        }
        String message = "下载完成：新增 " + downloaded + " 段，跳过 " + skipped + " 段，失败 " + failed + " 段";
        log.info("摄像头[{}] {} ~ {} {}", camera.getName(), from, to, message);
        return new DownloadSummary(downloaded, skipped, failed, message);
    }

    /**
     * 查询可下载的录像分段：优先云存储，其次本地（SD 卡）录像。
     * 查询接口无权限或不可用时由调用方退化为整段下载。
     */
    private List<RecordingSpan> resolveSpans(EzvizAccount account, Camera camera,
                                             LocalDateTime from, LocalDateTime to) {
        List<RecordingSpan> spans = new ArrayList<>();
        try {
            spans = ezvizClient.listRecordingSpans(account, camera.getDeviceSerial(),
                    camera.getChannelNo(), from, to, 1);
            log.info("云存储录像分段 {} 段", spans.size());
        } catch (Exception e) {
            log.warn("查询云存储录像失败: {}", e.getMessage());
        }
        if (!spans.isEmpty()) {
            return spans;
        }
        try {
            spans = ezvizClient.listRecordingSpans(account, camera.getDeviceSerial(),
                    camera.getChannelNo(), from, to, 2);
            log.info("本地录像分段 {} 段", spans.size());
        } catch (Exception e) {
            log.warn("查询本地录像失败: {}", e.getMessage());
        }
        return spans;
    }

    /**
     * @return 1 已下载；0 跳过；-1 失败
     */
    private int downloadPiece(EzvizAccount account, Camera camera, RecordingSpan piece, String directUrl,
                              boolean autoAnalyze, String tasks, String source) {
        LocalDateTime begin = piece.begin();
        LocalDateTime end = piece.end();
        VideoRecord record = videoRepository
                .findFirstByCameraIdAndBeginTimeAndEndTime(camera.getId(), begin, end)
                .orElse(null);
        if (record != null && ("READY".equals(record.getStatus()) || "DOWNLOADING".equals(record.getStatus()))) {
            return 0;
        }
        if (record == null) {
            record = new VideoRecord();
            record.setCameraId(camera.getId());
            record.setBeginTime(begin);
            record.setEndTime(end);
        }
        record.setCameraName(camera.getName());
        record.setDeviceSerial(camera.getDeviceSerial());
        record.setChannelNo(camera.getChannelNo());
        record.setSource(source);
        record.setStatus("DOWNLOADING");
        record.setMessage(null);
        record = videoRepository.save(record);

        Path part = null;
        try {
            String url = directUrl;
            if (url == null || url.isBlank()) {
                url = ezvizClient.getHlsAddress(account, camera.getDeviceSerial(),
                        camera.getChannelNo(), begin, end);
            }
            Path target = archiveService.targetPath(camera, begin, end);
            part = target.resolveSibling(target.getFileName() + ".part");
            long timeout = Math.max(300, Duration.between(begin, end).toMinutes() * 180);
            mediaTools.downloadHls(url, part, timeout);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);

            double duration = mediaTools.probeDurationSeconds(target);
            if (duration <= 0) {
                duration = Duration.between(begin, end).getSeconds();
            }
            record.setFilePath(target.toAbsolutePath().toString());
            record.setRelPath(archiveService.relativePath(target));
            record.setFileSize(archiveService.fileSize(target));
            record.setDurationSec(Math.round(duration * 10) / 10.0);
            record.setStatus("READY");
            record.setMessage(null);
            videoRepository.save(record);

            if (autoAnalyze && camera.isAnalysisEnabled()) {
                try {
                    analysisService.submit(record.getId(), tasks, "SCHEDULED");
                } catch (Exception e) {
                    log.warn("自动提交分析失败: {}", e.getMessage());
                }
            }
            return 1;
        } catch (Exception e) {
            log.warn("下载录像 {} ~ {} 失败: {}", begin, end, e.getMessage());
            record.setStatus("FAILED");
            record.setMessage(truncate(e.getMessage(), 500));
            videoRepository.save(record);
            if (part != null) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                    // ignore
                }
            }
            return -1;
        }
    }

    /** 每天 03:30 清理超过保留期的视频文件（保留分析报告与记录） */
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanupExpiredVideos() {
        if (!props.getScheduler().isEnabled()) {
            return;
        }
        int retentionDays = props.getStorage().getRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<VideoRecord> expired = videoRepository.findByStatusAndBeginTimeBefore("READY", cutoff);
        int deleted = 0;
        for (VideoRecord video : expired) {
            archiveService.deleteVideo(video);
            if (video.getFilePath() != null) {
                archiveService.cleanupEmptyDirectories(Path.of(video.getFilePath()).getParent());
            }
            video.setStatus("DELETED");
            video.setMessage("已超过保留期 " + retentionDays + " 天，文件已清理");
            videoRepository.save(video);
            deleted++;
        }
        if (deleted > 0) {
            log.info("已清理 {} 个过期视频文件", deleted);
        }
    }

    private void updateRule(DownloadRule rule, String message) {
        rule.setLastRunAt(LocalDateTime.now());
        rule.setLastRunMessage(truncate(message, 500));
        ruleRepository.save(rule);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
