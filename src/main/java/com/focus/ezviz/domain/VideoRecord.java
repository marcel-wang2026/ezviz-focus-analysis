package com.focus.ezviz.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 已下载并归档的视频文件记录 */
@Entity
@Table(name = "t_video_record", indexes = {
        @Index(name = "idx_video_camera_time", columnList = "camera_id,begin_time"),
        @Index(name = "idx_video_status", columnList = "status")
})
public class VideoRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "camera_id", nullable = false)
    private Long cameraId;

    @Column(nullable = false, length = 128)
    private String cameraName;

    @Column(nullable = false, length = 64)
    private String deviceSerial;

    private int channelNo = 1;

    /** 视频覆盖的开始时间 */
    @Column(name = "begin_time", nullable = false)
    private LocalDateTime beginTime;

    /** 视频覆盖的结束时间 */
    @Column(nullable = false)
    private LocalDateTime endTime;

    private double durationSec;

    private long fileSize;

    /** 归档绝对路径 */
    @Column(length = 600)
    private String filePath;

    /** 相对归档根目录的路径，用于 Web 播放 */
    @Column(length = 400)
    private String relPath;

    /** PENDING / DOWNLOADING / READY / FAILED / DELETED */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    /** 数据来源：SCHEDULED / MANUAL / IMPORT */
    @Column(nullable = false, length = 20)
    private String source = "SCHEDULED";

    /** 萤石侧文件标识（转封装任务 fileNodeId 等） */
    @Column(length = 200)
    private String remoteFileId;

    @Column(length = 500)
    private String message;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCameraId() { return cameraId; }
    public void setCameraId(Long cameraId) { this.cameraId = cameraId; }
    public String getCameraName() { return cameraName; }
    public void setCameraName(String cameraName) { this.cameraName = cameraName; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public int getChannelNo() { return channelNo; }
    public void setChannelNo(int channelNo) { this.channelNo = channelNo; }
    public LocalDateTime getBeginTime() { return beginTime; }
    public void setBeginTime(LocalDateTime beginTime) { this.beginTime = beginTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public double getDurationSec() { return durationSec; }
    public void setDurationSec(double durationSec) { this.durationSec = durationSec; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getRelPath() { return relPath; }
    public void setRelPath(String relPath) { this.relPath = relPath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getRemoteFileId() { return remoteFileId; }
    public void setRemoteFileId(String remoteFileId) { this.remoteFileId = remoteFileId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
