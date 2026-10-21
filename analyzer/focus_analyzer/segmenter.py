"""学习时间段 / 休息间隔切分与聚合统计。

- ``focusSegments``: 连续 ``FOCUSED`` 片段合并而成（人在座且专注/学习）。
- ``breaks``: 连续 ``ABSENT`` 片段合并而成（休息/离开间隔）。
- ``segments``: 按 ``segment_sec`` 分块的时段统计。
"""

from __future__ import annotations

from typing import List, Optional

from .scoring import ScoringConfig, TimelineSample
from .utils import (
    EVENT_LEAVE,
    STATE_ABSENT,
    STATE_FOCUSED,
    mean,
    sec,
)


def _step(cfg: ScoringConfig) -> float:
    return 1.0 / max(0.01, cfg.sample_fps)


def _run_duration(run: List[TimelineSample], cfg: ScoringConfig) -> float:
    if not run:
        return 0.0
    if len(run) == 1:
        return _step(cfg)
    return (run[-1].sec - run[0].sec) + _step(cfg)


def _merge_runs(
    samples: List[TimelineSample], states, cfg: ScoringConfig
) -> List[List[TimelineSample]]:
    runs: List[List[TimelineSample]] = []
    current: List[TimelineSample] = []
    for sample in samples:
        if sample.state in states:
            current.append(sample)
        else:
            if current:
                runs.append(current)
            current = []
    if current:
        runs.append(current)
    return runs


def aggregate(
    samples: List[TimelineSample],
    cfg: ScoringConfig,
    start_sec: Optional[float] = None,
    end_sec: Optional[float] = None,
) -> dict:
    """计算一组采样点的聚合指标（秒均保留 1 位小数）。"""
    if not samples:
        return {
            "focusScore": 0.0,
            "durationSec": 0.0,
            "studyDurationSec": 0.0,
            "distractedSec": 0.0,
            "absentSec": 0.0,
            "phoneSec": 0.0,
            "handPlayingSec": 0.0,
            "headDownSec": 0.0,
            "focusRatio": 0.0,
        }

    step = _step(cfg)
    by_state = {
        STATE_FOCUSED: 0.0,
        "DISTRACTED": 0.0,
        STATE_ABSENT: 0.0,
        "PHONE": 0.0,
        "HAND_PLAYING": 0.0,
    }
    head_down_sec = 0.0
    for sample in samples:
        by_state[sample.state] = by_state.get(sample.state, 0.0) + step
        if sample.head_down and sample.state != STATE_ABSENT:
            head_down_sec += step

    if start_sec is None:
        start_sec = samples[0].sec
    if end_sec is None:
        end_sec = samples[-1].sec + step
    duration = max(step, end_sec - start_sec)

    study_sec = by_state[STATE_FOCUSED]
    focus_ratio = study_sec / duration if duration > 0 else 0.0

    return {
        "focusScore": sec(mean([s.focus_score for s in samples])),
        "durationSec": sec(duration),
        "studyDurationSec": sec(study_sec),
        "distractedSec": sec(by_state["DISTRACTED"]),
        "absentSec": sec(by_state[STATE_ABSENT]),
        "phoneSec": sec(by_state["PHONE"]),
        "handPlayingSec": sec(by_state["HAND_PLAYING"]),
        "headDownSec": sec(head_down_sec),
        "focusRatio": round(focus_ratio, 3),
    }


def build_focus_segments(samples: List[TimelineSample], cfg: ScoringConfig) -> List[dict]:
    """连续 FOCUSED 片段 -> focusSegments。"""
    result: List[dict] = []
    for run in _merge_runs(samples, {STATE_FOCUSED}, cfg):
        duration = _run_duration(run, cfg)
        if duration + 1e-6 < cfg.focus_min_sec:
            continue
        result.append(
            {
                "startSec": sec(run[0].sec),
                "endSec": sec(run[-1].sec),
                "durationSec": sec(duration),
                "focusScore": sec(mean([s.focus_score for s in run])),
                "state": STATE_FOCUSED,
            }
        )
    return result


def build_breaks(samples: List[TimelineSample], cfg: ScoringConfig) -> List[dict]:
    """连续 ABSENT 片段 -> breaks。"""
    result: List[dict] = []
    for run in _merge_runs(samples, {STATE_ABSENT}, cfg):
        duration = _run_duration(run, cfg)
        if duration + 1e-6 < cfg.absent_min_sec:
            continue
        result.append(
            {
                "startSec": sec(run[0].sec),
                "endSec": sec(run[-1].sec),
                "durationSec": sec(duration),
                "reason": STATE_ABSENT,
            }
        )
    return result


def build_leave_events(samples: List[TimelineSample], cfg: ScoringConfig) -> List[dict]:
    """连续 ABSENT 片段 -> LEAVE_SEAT 事件。"""
    result: List[dict] = []
    for run in _merge_runs(samples, {STATE_ABSENT}, cfg):
        duration = _run_duration(run, cfg)
        if duration + 1e-6 < cfg.absent_min_sec:
            continue
        result.append(
            {
                "type": EVENT_LEAVE,
                "startSec": sec(run[0].sec),
                "endSec": sec(run[-1].sec),
                "durationSec": sec(duration),
                "confidence": 0.8,
                "note": "离开座位",
            }
        )
    return result


def build_segments(
    samples: List[TimelineSample],
    duration: float,
    cfg: ScoringConfig,
) -> List[dict]:
    """按固定时长分块得到 segments。"""
    if not samples:
        return []
    chunk = max(1.0, cfg.segment_sec)
    total = duration if duration > 0 else samples[-1].sec + _step(cfg)
    segments: List[dict] = []

    index = 0
    start = 0.0
    while start < total - 1e-6:
        end = min(total, start + chunk)
        chunk_samples = [s for s in samples if start - 1e-6 <= s.sec < end - 1e-6]
        if chunk_samples:
            metrics = aggregate(chunk_samples, cfg, start, end)
            dominant = _dominant_state(chunk_samples)
            segments.append(
                {
                    "index": index,
                    "startSec": sec(start),
                    "endSec": sec(end),
                    "durationSec": sec(end - start),
                    "focusScore": sec(metrics["focusScore"]),
                    "state": dominant,
                    "focusedSec": sec(metrics["studyDurationSec"]),
                    "distractedSec": sec(metrics["distractedSec"]),
                    "absentSec": sec(metrics["absentSec"]),
                    "phoneSec": sec(metrics["phoneSec"]),
                    "handPlayingSec": sec(metrics["handPlayingSec"]),
                    "headDownSec": sec(metrics["headDownSec"]),
                    "summary": _segment_summary(dominant, metrics),
                }
            )
            index += 1
        start = end
    return segments


def _dominant_state(samples: List[TimelineSample]) -> str:
    counts = {}
    for sample in samples:
        counts[sample.state] = counts.get(sample.state, 0) + 1
    return max(counts, key=counts.get) if counts else STATE_FOCUSED


def _segment_summary(state: str, metrics: dict) -> str:
    score = metrics["focusScore"]
    if state == STATE_FOCUSED and score >= 80:
        return "该时段专注度较高，以桌面书写/阅读为主，无明显走神。"
    if state == STATE_FOCUSED:
        return "该时段整体专注，但出现少量低头或转头。"
    if state == "DISTRACTED":
        return "该时段注意力涣散，出现持续转头或长时间低头。"
    if state == "PHONE":
        return "该时段检测到使用手机，专注度明显下降。"
    if state == "HAND_PLAYING":
        return "该时段手部离开桌面、存在小动作，专注度偏低。"
    if state == STATE_ABSENT:
        return "该时段未检测到学生在座，视为休息/离开。"
    return "该时段状态平稳。"


def overall_summary(metrics: dict, cfg: ScoringConfig) -> str:
    """生成整体中文总结（2-4 句）。"""
    score = metrics["focusScore"]
    ratio = metrics["focusRatio"]
    if score >= 85:
        head = "整体专注度优秀，学习状态稳定。"
    elif score >= 70:
        head = "整体专注度良好，能够较长时间保持学习状态。"
    elif score >= 50:
        head = "整体专注度一般，存在一定时间的走神或小动作。"
    else:
        head = "整体专注度偏低，走神、玩手或使用手机的时间较多。"

    parts = [head, f"有效学习时长约 {sec(metrics['studyDurationSec'])} 秒，专注占比约 {round(ratio * 100, 1)}%。"]
    if metrics["phoneSec"] > 0:
        parts.append(f"其中玩手机约 {sec(metrics['phoneSec'])} 秒，建议减少手机干扰。")
    if metrics["handPlayingSec"] > 0:
        parts.append(f"玩手/小动作约 {sec(metrics['handPlayingSec'])} 秒。")
    if metrics["absentSec"] > 0:
        parts.append(f"离开座位/休息约 {sec(metrics['absentSec'])} 秒。")
    return "".join(parts)
