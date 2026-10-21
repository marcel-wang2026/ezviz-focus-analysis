package com.focus.ezviz.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 分析报告：包含视频时长、学习时长、每段学习间隔、每段学习时间段的专注度总结。
 */
@Entity
@Table(name = "t_analysis_report", indexes = @Index(name = "idx_report_video", columnList = "video_id"))
public class AnalysisReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(name = "camera_id")
    private Long cameraId;

    @Column(length = 128)
    private String cameraName;

    @Column(name = "begin_time")
    private LocalDateTime beginTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** 视频总时长（秒） */
    private double videoDurationSec;

    /** 学习时长（秒），即被判定处在学习状态的时间累计 */
    private double studyDurationSec;

    /** 专注度总分 0-100 */
    private double focusScore;

    /** 专注率 0-1 */
    private double focusRatio;

    private double distractedSec;

    private double absentSec;

    private double phoneSec;

    private double handPlayingSec;

    private double headDownSec;

    /** 学习时间段（JSON 数组） */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String studySegmentsJson;

    /** 学习间隔（休息/离开）JSON 数组 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String breaksJson;

    /** 异常事件（玩手机/走神/玩手）JSON 数组 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String eventsJson;

    /** 逐段专注度总结文本 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String segmentsSummary;

    /** 整体总结 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String overallSummary;

    /** 音频转写全文 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String audioText;

    /** 使用的算法与来源说明 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String algorithmInfo;

    /** 完整原始 JSON 结果 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String rawJson;

    /** 原始 JSON 落盘路径 */
    @Column(length = 600)
    private String jsonPath;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVideoId() { return videoId; }
    public void setVideoId(Long videoId) { this.videoId = videoId; }
    public Long getCameraId() { return cameraId; }
    public void setCameraId(Long cameraId) { this.cameraId = cameraId; }
    public String getCameraName() { return cameraName; }
    public void setCameraName(String cameraName) { this.cameraName = cameraName; }
    public LocalDateTime getBeginTime() { return beginTime; }
    public void setBeginTime(LocalDateTime beginTime) { this.beginTime = beginTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public double getVideoDurationSec() { return videoDurationSec; }
    public void setVideoDurationSec(double v) { this.videoDurationSec = v; }
    public double getStudyDurationSec() { return studyDurationSec; }
    public void setStudyDurationSec(double v) { this.studyDurationSec = v; }
    public double getFocusScore() { return focusScore; }
    public void setFocusScore(double focusScore) { this.focusScore = focusScore; }
    public double getFocusRatio() { return focusRatio; }
    public void setFocusRatio(double focusRatio) { this.focusRatio = focusRatio; }
    public double getDistractedSec() { return distractedSec; }
    public void setDistractedSec(double v) { this.distractedSec = v; }
    public double getAbsentSec() { return absentSec; }
    public void setAbsentSec(double v) { this.absentSec = v; }
    public double getPhoneSec() { return phoneSec; }
    public void setPhoneSec(double v) { this.phoneSec = v; }
    public double getHandPlayingSec() { return handPlayingSec; }
    public void setHandPlayingSec(double v) { this.handPlayingSec = v; }
    public double getHeadDownSec() { return headDownSec; }
    public void setHeadDownSec(double v) { this.headDownSec = v; }
    public String getStudySegmentsJson() { return studySegmentsJson; }
    public void setStudySegmentsJson(String v) { this.studySegmentsJson = v; }
    public String getBreaksJson() { return breaksJson; }
    public void setBreaksJson(String v) { this.breaksJson = v; }
    public String getEventsJson() { return eventsJson; }
    public void setEventsJson(String v) { this.eventsJson = v; }
    public String getSegmentsSummary() { return segmentsSummary; }
    public void setSegmentsSummary(String v) { this.segmentsSummary = v; }
    public String getOverallSummary() { return overallSummary; }
    public void setOverallSummary(String v) { this.overallSummary = v; }
    public String getAudioText() { return audioText; }
    public void setAudioText(String v) { this.audioText = v; }
    public String getAlgorithmInfo() { return algorithmInfo; }
    public void setAlgorithmInfo(String v) { this.algorithmInfo = v; }
    public String getRawJson() { return rawJson; }
    public void setRawJson(String v) { this.rawJson = v; }
    public String getJsonPath() { return jsonPath; }
    public void setJsonPath(String v) { this.jsonPath = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Transient
    public String getVideoDurationText() { return formatDuration(videoDurationSec); }

    @Transient
    public String getStudyDurationText() { return formatDuration(studyDurationSec); }

    @Transient
    public String getFocusRatioText() {
        return String.format("%.0f%%", focusRatio * 100);
    }

    private static String formatDuration(double seconds) {
        int total = (int) Math.round(seconds);
        return String.format("%d 分 %02d 秒", total / 60, total % 60);
    }
}
