package com.focus.ezviz.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.focus.ezviz.analysis.AnalyzerException;
import com.focus.ezviz.domain.AnalysisReport;
import com.focus.ezviz.domain.VideoRecord;
import com.focus.ezviz.report.BreakDto;
import com.focus.ezviz.report.StudySegmentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 把算法返回的原始 JSON 转换成可持久化、可展示的分析报告。
 *
 * <p>报告包含：视频时长、学习时长、每段学习间隔、每段学习时间段的专注度总结。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ObjectMapper objectMapper;
    private final StudySegmentBuilder segmentBuilder;

    public ReportService(ObjectMapper objectMapper, StudySegmentBuilder segmentBuilder) {
        this.objectMapper = objectMapper;
        this.segmentBuilder = segmentBuilder;
    }

    public AnalysisReport build(VideoRecord video, JsonNode root, String jsonPath) {
        validate(root);
        JsonNode overall = root.path("overall");
        double videoDuration = doubleOf(overall, "videoDurationSec", root.path("video").path("durationSec").asDouble());
        if (videoDuration <= 0) {
            videoDuration = video.getDurationSec();
        }

        StudySegmentBuilder.Result result = segmentBuilder.build(root, videoDuration);

        AnalysisReport report = new AnalysisReport();
        report.setVideoId(video.getId());
        report.setCameraId(video.getCameraId());
        report.setCameraName(video.getCameraName());
        report.setBeginTime(video.getBeginTime());
        report.setEndTime(video.getEndTime());
        report.setJsonPath(jsonPath);

        report.setVideoDurationSec(round1(videoDuration));
        report.setStudyDurationSec(result.studyDurationSec());
        report.setFocusScore(result.weightedFocusScore() > 0
                ? result.weightedFocusScore()
                : round1(doubleOf(overall, "focusScore", 0)));
        report.setFocusRatio(round3(videoDuration > 0 ? result.studyDurationSec() / videoDuration : 0));
        report.setDistractedSec(result.distractedSec());
        report.setAbsentSec(result.absentSec());
        report.setPhoneSec(result.phoneSec());
        report.setHandPlayingSec(result.handPlayingSec());
        report.setHeadDownSec(round1(doubleOf(overall, "headDownSec", 0)));

        report.setStudySegmentsJson(writeJson(result.segments()));
        report.setBreaksJson(writeJson(result.breaks()));
        report.setEventsJson(writeJsonArray(root.path("events")));
        report.setSegmentsSummary(buildSegmentsSummary(result.segments()));
        report.setOverallSummary(buildOverallSummary(root, result, videoDuration));
        report.setAudioText(root.path("audio").path("fullText").asText(""));
        report.setAlgorithmInfo(writeJsonArray(root.path("algorithms")));
        try {
            report.setRawJson(objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            log.warn("序列化算法原始结果失败", e);
            report.setRawJson("{}");
        }
        return report;
    }

    /** 逐段学习时间段的专注度总结 */
    private String buildSegmentsSummary(List<StudySegmentDto> segments) {
        if (segments.isEmpty()) {
            return "未识别到有效学习时间段。";
        }
        StringBuilder sb = new StringBuilder();
        for (StudySegmentDto segment : segments) {
            sb.append("【学习时间段 ").append(segment.getIndex() + 1).append("】")
                    .append(segment.getStartClock()).append(" - ").append(segment.getEndClock())
                    .append("（").append(segment.getDurationText()).append("）")
                    .append("｜状态 ").append(segment.getStateLabel())
                    .append("｜专注度 ").append(segment.getFocusScore()).append(" 分")
                    .append("｜专注 ").append(formatDuration(segment.getFocusedSec()))
                    .append("，走神 ").append(formatDuration(segment.getDistractedSec()))
                    .append("，离开 ").append(formatDuration(segment.getAbsentSec()));
            if (segment.getSummary() != null && !segment.getSummary().isBlank()) {
                sb.append("｜").append(segment.getSummary());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 整体总结：视频时长、学习时长、间隔、异常事件 */
    private String buildOverallSummary(JsonNode root, StudySegmentBuilder.Result result, double videoDuration) {
        JsonNode overall = root.path("overall");
        StringBuilder sb = new StringBuilder();
        String baseSummary = overall.path("summary").asText("");
        if (!baseSummary.isBlank()) {
            sb.append(baseSummary);
        }
        List<String> parts = new ArrayList<>();
        parts.add("视频总时长 " + formatDuration(videoDuration));
        parts.add("学习时长 " + formatDuration(result.studyDurationSec())
                + "（占比 " + percent(videoDuration > 0 ? result.studyDurationSec() / videoDuration : 0) + "）"
                + "，综合专注度 " + result.weightedFocusScore() + " 分");
        parts.add("共切分出 " + result.segments().size() + " 个学习时间段、"
                + result.breaks().size() + " 段学习间隔");
        if (result.phoneSec() > 0) {
            parts.add("玩手机累计 " + formatDuration(result.phoneSec()));
        }
        if (result.handPlayingSec() > 0) {
            parts.add("玩手/小动作累计 " + formatDuration(result.handPlayingSec()));
        }
        if (result.distractedSec() > 0) {
            parts.add("走神累计 " + formatDuration(result.distractedSec()));
        }
        if (result.absentSec() > 0) {
            parts.add("离开座位累计 " + formatDuration(result.absentSec()));
        }
        sb.append('\n').append(String.join("；", parts)).append('。');

        if (!result.breaks().isEmpty()) {
            List<String> breakTexts = new ArrayList<>();
            for (BreakDto b : result.breaks()) {
                breakTexts.add(b.getStartClock() + "-" + b.getEndClock() + "（" + b.getDurationText() + "）");
            }
            sb.append("\n学习间隔：").append(String.join("、", breakTexts)).append('。');
        }

        JsonNode audio = root.path("audio");
        if (audio.path("fullText").asText("").isBlank() && audio.path("degraded").asBoolean(false)) {
            sb.append("\n音频转写未启用或缺少 ASR 依赖，报告中不含语音文本。");
        }

        JsonNode warnings = root.path("warnings");
        if (warnings.isArray() && !warnings.isEmpty()) {
            List<String> list = new ArrayList<>();
            warnings.forEach(w -> list.add(w.asText()));
            sb.append("\n算法提示：").append(String.join("；", list));
        }
        return sb.toString().trim();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String writeJsonArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static double doubleOf(JsonNode node, String field, double defaultValue) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : defaultValue;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    public static String formatDuration(double seconds) {
        int total = (int) Math.round(seconds);
        int hour = total / 3600;
        int minute = (total % 3600) / 60;
        int second = total % 60;
        if (hour > 0) {
            return hour + " 小时 " + minute + " 分";
        }
        if (minute > 0) {
            return minute + " 分 " + second + " 秒";
        }
        return second + " 秒";
    }

    private static String percent(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }

    /** 算法结果健壮性校验 */
    public void validate(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull() || !root.has("overall")) {
            throw new AnalyzerException("算法结果缺少 overall 字段，无法生成报告");
        }
    }
}
