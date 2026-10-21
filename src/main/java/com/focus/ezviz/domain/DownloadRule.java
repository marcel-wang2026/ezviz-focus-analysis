package com.focus.ezviz.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 下载与存储规则：支持指定摄像头、指定时间周期、按时间段切分下载并归档。
 */
@Entity
@Table(name = "t_download_rule")
public class DownloadRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "camera_id", nullable = false)
    private Long cameraId;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 调度周期 cron（Spring 六段式），例如 0 0/30 * * * * 表示每 30 分钟 */
    @Column(nullable = false, length = 64)
    private String cron = "0 0/30 * * * *";

    /** 每次调度回看过去多少分钟的视频 */
    private int windowMinutes = 30;

    /** 单个下载文件的时间长度（分钟），用于把长区间切成小文件 */
    private int segmentMinutes = 30;

    /** 视频归档保留天数，0 表示永久保留 */
    private int retentionDays = 30;

    /** 下载完成后是否自动提交特征分析 */
    @Column(nullable = false)
    private boolean autoAnalyze = true;

    /** 自动分析使用的算法任务集合 */
    @Column(length = 200)
    private String analyzeTasks = "focus,distraction,hands,phone,audio";

    private LocalDateTime lastRunAt;

    @Column(length = 500)
    private String lastRunMessage;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getCameraId() { return cameraId; }
    public void setCameraId(Long cameraId) { this.cameraId = cameraId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public int getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    public int getSegmentMinutes() { return segmentMinutes; }
    public void setSegmentMinutes(int segmentMinutes) { this.segmentMinutes = segmentMinutes; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public boolean isAutoAnalyze() { return autoAnalyze; }
    public void setAutoAnalyze(boolean autoAnalyze) { this.autoAnalyze = autoAnalyze; }
    public String getAnalyzeTasks() { return analyzeTasks; }
    public void setAnalyzeTasks(String analyzeTasks) { this.analyzeTasks = analyzeTasks; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public String getLastRunMessage() { return lastRunMessage; }
    public void setLastRunMessage(String lastRunMessage) { this.lastRunMessage = lastRunMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
