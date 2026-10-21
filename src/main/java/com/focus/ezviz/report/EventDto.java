package com.focus.ezviz.report;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** 异常事件：玩手机、走神、玩手、离开座位 */
public class EventDto {

    private String type = "";
    private double startSec;
    private double endSec;
    private double durationSec;
    private double confidence;
    private String note = "";

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public double getStartSec() { return startSec; }
    public void setStartSec(double startSec) { this.startSec = startSec; }
    public double getEndSec() { return endSec; }
    public void setEndSec(double endSec) { this.endSec = endSec; }
    public double getDurationSec() { return durationSec; }
    public void setDurationSec(double durationSec) { this.durationSec = durationSec; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

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
    public String getTypeLabel() {
        return switch (type == null ? "" : type) {
            case "PHONE_USE" -> "玩手机";
            case "DISTRACTION" -> "走神";
            case "HAND_PLAYING" -> "玩手/小动作";
            case "LEAVE_SEAT" -> "离开座位";
            default -> type == null ? "未知" : type;
        };
    }
}
