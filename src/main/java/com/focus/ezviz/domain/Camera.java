package com.focus.ezviz.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 需要下载/分析的摄像头 */
@Entity
@Table(name = "t_camera",
        uniqueConstraints = @UniqueConstraint(name = "uk_camera_serial", columnNames = {"account_id", "deviceSerial", "channelNo"}))
public class Camera {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 萤石设备序列号 */
    @Column(nullable = false, length = 64)
    private String deviceSerial;

    private int channelNo = 1;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 是否允许提交算法分析 */
    @Column(nullable = false)
    private boolean analysisEnabled = true;

    /** 书桌区域 ROI，归一化坐标 x,y,w,h；为空表示整幅画面 */
    @Column(length = 100)
    private String roi;

    @Column(length = 500)
    private String remark;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public int getChannelNo() { return channelNo; }
    public void setChannelNo(int channelNo) { this.channelNo = channelNo; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAnalysisEnabled() { return analysisEnabled; }
    public void setAnalysisEnabled(boolean analysisEnabled) { this.analysisEnabled = analysisEnabled; }
    public String getRoi() { return roi; }
    public void setRoi(String roi) { this.roi = roi; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
