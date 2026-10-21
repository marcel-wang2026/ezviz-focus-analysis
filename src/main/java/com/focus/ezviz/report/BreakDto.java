package com.focus.ezviz.report;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** 学习间隔（休息 / 离开座位） */
public class BreakDto {

    private double startSec;
    private double endSec;
    private double durationSec;
    private String reason = "ABSENT";

    public BreakDto() {
    }

    public BreakDto(double startSec, double endSec, String reason) {
        this.startSec = startSec;
        this.endSec = endSec;
        this.durationSec = Math.max(0, endSec - startSec);
        this.reason = reason;
    }

    public double getStartSec() { return startSec; }
    public void setStartSec(double startSec) { this.startSec = startSec; }
    public double getEndSec() { return endSec; }
    public void setEndSec(double endSec) { this.endSec = endSec; }
    public double getDurationSec() { return durationSec; }
    public void setDurationSec(double durationSec) { this.durationSec = durationSec; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    @JsonIgnore
    public String getStartClock() {
        return StudySegmentDto.formatClock(startSec);
    }

    @JsonIgnore
    public String getEndClock() {
        return StudySegmentDto.formatClock(endSec);
    }

    @JsonIgnore
    public String getDurationText() {
        int total = (int) Math.round(durationSec);
        return String.format("%d 分 %02d 秒", total / 60, total % 60);
    }

    @JsonIgnore
    public String getReasonLabel() {
        return "ABSENT".equals(reason) ? "离开座位 / 休息" : "休息";
    }
}
