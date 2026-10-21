package com.focus.ezviz.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.focus.ezviz.report.BreakDto;
import com.focus.ezviz.report.StudySegmentDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从算法逐秒时间线中切分「学习时间段」与「学习间隔」，并生成每段的专注度统计与总结。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>离开座位（ABSENT）视为非学习；短暂离开（≤2 分钟）不打断学习时间段</li>
 *   <li>学习时间段过长时按 30 分钟切片，便于逐段总结</li>
 *   <li>两段学习之间 ≥30 秒的空档记为学习间隔</li>
 * </ul>
 */
@Service
public class StudySegmentBuilder {

    private static final double GAP_TOLERANCE_SEC = 120;
    private static final double MIN_INTERVAL_SEC = 30;
    private static final double MIN_BREAK_SEC = 30;
    private static final double MAX_SEGMENT_SEC = 1800;
    private static final double MIN_TAIL_SEC = 300;

    public record Result(List<StudySegmentDto> segments,
                         List<BreakDto> breaks,
                         double studyDurationSec,
                         double breakDurationSec,
                         double focusedSec,
                         double distractedSec,
                         double absentSec,
                         double phoneSec,
                         double handPlayingSec,
                         double weight,
                         double weightedFocusScore) {
    }

    private record Sample(double sec, double dt, String state, double focusScore) {
    }

    public Result build(JsonNode root, double videoDurationSec) {
        List<Sample> samples = parseTimeline(root.path("timeline"), videoDurationSec);
        if (!samples.isEmpty()) {
            return fromTimeline(samples);
        }
        return fromFocusSegments(root, videoDurationSec);
    }

    // ------------------------------------------------------------------ timeline

    private Result fromTimeline(List<Sample> samples) {
        List<double[]> rawIntervals = studyIntervals(samples);
        List<StudySegmentDto> segments = new ArrayList<>();
        for (double[] interval : rawIntervals) {
            splitAndAccumulate(samples, interval[0], interval[1], segments);
        }
        segments.sort(Comparator.comparingDouble(StudySegmentDto::getStartSec));
        for (int i = 0; i < segments.size(); i++) {
            segments.get(i).setIndex(i);
            segments.get(i).setSummary(summarize(segments.get(i)));
        }

        List<BreakDto> breaks = new ArrayList<>();
        double cursor = 0;
        for (double[] interval : rawIntervals) {
            if (interval[0] - cursor >= MIN_BREAK_SEC) {
                breaks.add(new BreakDto(cursor, interval[0], "ABSENT"));
            }
            cursor = Math.max(cursor, interval[1]);
        }
        double videoEnd = samples.get(samples.size() - 1).sec + samples.get(samples.size() - 1).dt;
        if (segments.isEmpty()) {
            return aggregate(segments, List.of(new BreakDto(0, videoEnd, "ABSENT")));
        }
        double lastEnd = rawIntervals.get(rawIntervals.size() - 1)[1];
        if (videoEnd - lastEnd >= MIN_BREAK_SEC) {
            breaks.add(new BreakDto(lastEnd, videoEnd, "ABSENT"));
        }
        return aggregate(segments, breaks);
    }

    private List<double[]> studyIntervals(List<Sample> samples) {
        List<double[]> intervals = new ArrayList<>();
        Double start = null;
        double lastStudyEnd = 0;
        for (Sample sample : samples) {
            boolean study = !"ABSENT".equals(sample.state());
            if (study) {
                if (start == null) {
                    start = sample.sec();
                } else if (sample.sec() - lastStudyEnd > GAP_TOLERANCE_SEC) {
                    addInterval(intervals, start, lastStudyEnd);
                    start = sample.sec();
                }
                lastStudyEnd = sample.sec() + sample.dt();
            }
        }
        if (start != null) {
            addInterval(intervals, start, lastStudyEnd);
        }
        return intervals;
    }

    private void addInterval(List<double[]> intervals, double start, double end) {
        if (end - start >= MIN_INTERVAL_SEC) {
            intervals.add(new double[]{start, end});
        }
    }

    private void splitAndAccumulate(List<Sample> samples, double start, double end,
                                    List<StudySegmentDto> segments) {
        double cursor = start;
        while (cursor < end) {
            double chunkEnd = Math.min(cursor + MAX_SEGMENT_SEC, end);
            if (end - chunkEnd < MIN_TAIL_SEC) {
                chunkEnd = end;
            }
            StudySegmentDto segment = accumulate(samples, cursor, chunkEnd);
            if (segment.getDurationSec() >= 1) {
                segment.setState(dominantState(segment));
                segments.add(segment);
            }
            cursor = chunkEnd;
        }
    }

    private StudySegmentDto accumulate(List<Sample> samples, double from, double to) {
        double focused = 0, distracted = 0, absent = 0, phone = 0, hand = 0;
        double scoreSum = 0;
        double weight = 0;
        for (Sample sample : samples) {
            if (sample.sec() + sample.dt() <= from || sample.sec() >= to) {
                continue;
            }
            double overlap = Math.min(sample.sec() + sample.dt(), to) - Math.max(sample.sec(), from);
            if (overlap <= 0) {
                continue;
            }
            switch (sample.state()) {
                case "ABSENT" -> absent += overlap;
                case "DISTRACTED" -> distracted += overlap;
                case "PHONE" -> phone += overlap;
                case "HAND_PLAYING" -> hand += overlap;
                default -> focused += overlap;
            }
            scoreSum += sample.focusScore() * overlap;
            weight += overlap;
        }
        StudySegmentDto dto = new StudySegmentDto();
        dto.setStartSec(round1(from));
        dto.setEndSec(round1(to));
        dto.setDurationSec(round1(to - from));
        dto.setFocusedSec(round1(focused));
        dto.setDistractedSec(round1(distracted));
        dto.setAbsentSec(round1(absent));
        dto.setPhoneSec(round1(phone));
        dto.setHandPlayingSec(round1(hand));
        dto.setFocusScore(weight > 0 ? round1(scoreSum / weight) : 0);
        return dto;
    }

    private String dominantState(StudySegmentDto segment) {
        double duration = Math.max(segment.getDurationSec(), 1);
        if (segment.getPhoneSec() / duration >= 0.25) {
            return "PHONE";
        }
        if (segment.getHandPlayingSec() / duration >= 0.25) {
            return "HAND_PLAYING";
        }
        if (segment.getDistractedSec() / duration >= 0.3) {
            return "DISTRACTED";
        }
        if (segment.getAbsentSec() / duration >= 0.5) {
            return "ABSENT";
        }
        return "FOCUSED";
    }

    private Result aggregate(List<StudySegmentDto> segments, List<BreakDto> breaks) {
        double study = 0, focused = 0, distracted = 0, absent = 0, phone = 0, hand = 0;
        double scoreWeighted = 0;
        for (StudySegmentDto s : segments) {
            study += s.getDurationSec();
            focused += s.getFocusedSec();
            distracted += s.getDistractedSec();
            absent += s.getAbsentSec();
            phone += s.getPhoneSec();
            hand += s.getHandPlayingSec();
            scoreWeighted += s.getFocusScore() * s.getDurationSec();
        }
        double breakDuration = breaks.stream().mapToDouble(BreakDto::getDurationSec).sum();
        return new Result(segments, breaks, round1(study), round1(breakDuration),
                round1(focused), round1(distracted), round1(absent), round1(phone), round1(hand),
                study, study > 0 ? round1(scoreWeighted / study) : 0);
    }

    // ------------------------------------------------------------------ fallback

    private Result fromFocusSegments(JsonNode root, double videoDurationSec) {
        List<StudySegmentDto> segments = new ArrayList<>();
        JsonNode focusSegments = root.path("focusSegments");
        if (focusSegments.isArray() && !focusSegments.isEmpty()) {
            int index = 0;
            for (JsonNode node : focusSegments) {
                StudySegmentDto dto = new StudySegmentDto();
                dto.setIndex(index++);
                dto.setStartSec(node.path("startSec").asDouble(0));
                dto.setEndSec(node.path("endSec").asDouble(0));
                dto.setDurationSec(node.path("durationSec").asDouble(dto.getEndSec() - dto.getStartSec()));
                dto.setFocusScore(node.path("focusScore").asDouble(0));
                dto.setState(node.path("state").asText("FOCUSED"));
                dto.setFocusedSec(dto.getDurationSec());
                dto.setSummary(summarize(dto));
                segments.add(dto);
            }
        } else if (videoDurationSec > 0) {
            JsonNode overall = root.path("overall");
            StudySegmentDto dto = new StudySegmentDto();
            dto.setStartSec(0);
            dto.setEndSec(round1(videoDurationSec));
            dto.setDurationSec(round1(videoDurationSec));
            dto.setFocusScore(overall.path("focusScore").asDouble(0));
            dto.setState(overall.path("studyDurationSec").asDouble(0) > 0 ? "FOCUSED" : "ABSENT");
            dto.setFocusedSec(overall.path("studyDurationSec").asDouble(0));
            dto.setDistractedSec(overall.path("distractedSec").asDouble(0));
            dto.setAbsentSec(overall.path("absentSec").asDouble(0));
            dto.setPhoneSec(overall.path("phoneSec").asDouble(0));
            dto.setHandPlayingSec(overall.path("handPlayingSec").asDouble(0));
            dto.setSummary(summarize(dto));
            segments.add(dto);
        }

        List<BreakDto> breaks = new ArrayList<>();
        JsonNode breakNodes = root.path("breaks");
        if (breakNodes.isArray()) {
            for (JsonNode node : breakNodes) {
                breaks.add(new BreakDto(node.path("startSec").asDouble(0), node.path("endSec").asDouble(0),
                        node.path("reason").asText("ABSENT")));
            }
        } else if (segments.size() >= 2) {
            for (int i = 1; i < segments.size(); i++) {
                double gapStart = segments.get(i - 1).getEndSec();
                double gapEnd = segments.get(i).getStartSec();
                if (gapEnd - gapStart >= MIN_BREAK_SEC) {
                    breaks.add(new BreakDto(gapStart, gapEnd, "ABSENT"));
                }
            }
        }
        return aggregate(segments, breaks);
    }

    // ------------------------------------------------------------------ parse

    private List<Sample> parseTimeline(JsonNode timeline, double videoDurationSec) {
        List<Sample> samples = new ArrayList<>();
        if (timeline == null || !timeline.isArray()) {
            return samples;
        }
        for (JsonNode node : timeline) {
            double sec = node.path("sec").asDouble(-1);
            if (sec < 0) {
                continue;
            }
            samples.add(new Sample(sec, 0,
                    node.path("state").asText("FOCUSED"),
                    node.path("focusScore").asDouble(0)));
        }
        samples.sort(Comparator.comparingDouble(Sample::sec));
        if (samples.isEmpty()) {
            return samples;
        }
        List<Sample> withDt = new ArrayList<>(samples.size());
        for (int i = 0; i < samples.size(); i++) {
            double dt;
            if (i + 1 < samples.size()) {
                dt = samples.get(i + 1).sec() - samples.get(i).sec();
            } else if (videoDurationSec > samples.get(i).sec()) {
                dt = videoDurationSec - samples.get(i).sec();
            } else if (i > 0) {
                dt = samples.get(i).sec() - samples.get(i - 1).sec();
            } else {
                dt = 1;
            }
            dt = Math.max(0.2, Math.min(dt, 10));
            withDt.add(new Sample(samples.get(i).sec(), dt, samples.get(i).state(), samples.get(i).focusScore()));
        }
        return withDt;
    }

    // ------------------------------------------------------------------ summary

    private String summarize(StudySegmentDto s) {
        List<String> issues = new ArrayList<>();
        if (s.getPhoneSec() >= 10) {
            issues.add("玩手机 " + Math.round(s.getPhoneSec() / 60.0 * 10) / 10.0 + " 分钟");
        }
        if (s.getHandPlayingSec() >= 15) {
            issues.add("玩手/小动作 " + Math.round(s.getHandPlayingSec() / 60.0 * 10) / 10.0 + " 分钟");
        }
        if (s.getDistractedSec() >= 15) {
            issues.add("走神 " + Math.round(s.getDistractedSec() / 60.0 * 10) / 10.0 + " 分钟");
        }
        if (s.getAbsentSec() >= 15) {
            issues.add("离开座位 " + Math.round(s.getAbsentSec() / 60.0 * 10) / 10.0 + " 分钟");
        }

        String comment;
        double score = s.getFocusScore();
        if (score >= 85) {
            comment = "专注度很高，学习状态稳定";
        } else if (score >= 70) {
            comment = "专注度良好，偶有短暂分心";
        } else if (score >= 50) {
            comment = "专注度一般，存在明显走神或小动作";
        } else {
            comment = "专注度偏低，建议缩短单次学习时长、清理桌面干扰物";
        }
        if (issues.isEmpty()) {
            return comment + "。";
        }
        return comment + "，其中" + String.join("、", issues) + "。";
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
