package com.focus.ezviz.report;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 学习时间段（报告中的一行），同时用于 JSON 持久化与页面展示。
 */
public class StudySegmentDto {

    private int index;
    private double startSec;
    private double endSec;
    private double durationSec;
    private double focusScore;
    private double focusedSec;
    private double distractedSec;
    private double absentSec;
    private double phoneSec;
    private double handPlayingSec;
    private double headDownSec;
    private String state = "FOCUSED";
    private String summary = "";

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
    public double getStartSec() { return startSec; }
    public void setStartSec(double startSec) { this.startSec = startSec; }
    public double getEndSec() { return endSec; }
    public void setEndSec(double endSec) { this.endSec = endSec; }
    public double getDurationSec() { return durationSec; }
    public void setDurationSec(double durationSec) { this.durationSec = durationSec; }
    public double getFocusScore() { return focusScore; }
    public void setFocusScore(double focusScore) { this.focusScore = focusScore; }
    public double getFocusedSec() { return focusedSec; }
    public void setFocusedSec(double focusedSec) { this.focusedSec = focusedSec; }
    public double getDistractedSec() { return distractedSec; }
    public void setDistractedSec(double distractedSec) { this.distractedSec = distractedSec; }
    public double getAbsentSec() { return absentSec; }
    public void setAbsentSec(double absentSec) { this.absentSec = absentSec; }
    public double getPhoneSec() { return phoneSec; }
    public void setPhoneSec(double phoneSec) { this.phoneSec = phoneSec; }
    public double getHandPlayingSec() { return handPlayingSec; }
    public void setHandPlayingSec(double handPlayingSec) { this.handPlayingSec = handPlayingSec; }
    public double getHeadDownSec() { return headDownSec; }
    public void setHeadDownSec(double headDownSec) { this.headDownSec = headDownSec; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    @JsonIgnore
    public String getStartClock() {
        return formatClock(startSec);
    }

    @JsonIgnore
    public String getEndClock() {
        return formatClock(endSec);
    }

    @JsonIgnore
    public String getDurationText() {
        int total = (int) Math.round(durationSec);
        return String.format("%d 分 %02d 秒", total / 60, total % 60);
    }

    @JsonIgnore
    public String getStateLabel() {
        return switch (state == null ? "" : state) {
            case "FOCUSED" -> "专注";
            case "DISTRACTED" -> "走神";
            case "PHONE" -> "玩手机";
            case "HAND_PLAYING" -> "玩手";
            case "ABSENT" -> "离开";
            default -> "未知";
        };
    }

    public static String formatClock(double seconds) {
        int total = (int) Math.round(seconds);
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }
}
