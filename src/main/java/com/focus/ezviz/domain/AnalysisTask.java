package com.focus.ezviz.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 某个视频的特征分析任务 */
@Entity
@Table(name = "t_analysis_task", indexes = @Index(name = "idx_task_video", columnList = "video_id"))
public class AnalysisTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(name = "camera_id")
    private Long cameraId;

    /** 逗号分隔的算法任务：focus,distraction,hands,phone,audio */
    @Column(nullable = false, length = 200)
    private String tasks = "focus,distraction,hands,phone,audio";

    /** PENDING / RUNNING / SUCCESS / FAILED / CANCELED */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    private int progress;

    @Column(length = 1000)
    private String message;

    @Column(name = "report_id")
    private Long reportId;

    /** MANUAL / SCHEDULED */
    @Column(nullable = false, length = 20)
    private String triggerType = "MANUAL";

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVideoId() { return videoId; }
    public void setVideoId(Long videoId) { this.videoId = videoId; }
    public Long getCameraId() { return cameraId; }
    public void setCameraId(Long cameraId) { this.cameraId = cameraId; }
    public String getTasks() { return tasks; }
    public void setTasks(String tasks) { this.tasks = tasks; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
