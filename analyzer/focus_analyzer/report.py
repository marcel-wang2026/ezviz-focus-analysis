"""最终 JSON 组装。

严格遵循 Java 侧依赖的输出契约：字段名、枚举值、数值类型全部固定。
"""

from __future__ import annotations

import random
from typing import Dict, List, Optional, Sequence

from .scoring import ScoringConfig, TimelineSample
from .utils import (
    STATE_ABSENT,
    STATE_FOCUSED,
    STATE_HAND_PLAYING,
    STATE_PHONE,
    sec,
)

VERSION = "1.0"
DEFAULT_TASKS = ("focus", "distraction", "hands", "phone", "audio")

# 算法目录：name -> (ref, url, 默认是否降级说明)
ALGO_CATALOG: Dict[str, Dict[str, str]] = {
    "focus": {
        "name": "head_pose_focus",
        "ref": "yinguobing/head-pose-estimation",
        "url": "https://github.com/yinguobing/head-pose-estimation",
    },
    "distraction": {
        "name": "temporal_distraction_rule",
        "ref": "classroom-behavior-recognition",
        "url": "https://github.com/search?q=classroom+behavior+recognition",
    },
    "hands": {
        "name": "hand_desk_interaction",
        "ref": "google-ai-edge/mediapipe",
        "url": "https://github.com/google-ai-edge/mediapipe",
    },
    "phone": {
        "name": "phone_hand_overlap",
        "ref": "ultralytics/ultralytics",
        "url": "https://github.com/ultralytics/ultralytics",
    },
    "audio": {
        "name": "speech_to_text",
        "ref": "SYSTRAN/faster-whisper",
        "url": "https://github.com/SYSTRAN/faster-whisper",
    },
    "study": {
        "name": "study_session_segmentation",
        "ref": "yinguobing/head-pose-estimation",
        "url": "https://github.com/yinguobing/head-pose-estimation",
    },
}


def build_algorithms(tasks: Sequence[str], degraded: Dict[str, bool]) -> List[dict]:
    """构造 ``algorithms`` 数组。"""
    result: List[dict] = []
    for task in tasks:
        meta = ALGO_CATALOG.get(task)
        if meta is None:
            continue
        result.append(
            {
                "name": meta["name"],
                "ref": meta["ref"],
                "url": meta["url"],
                "degraded": bool(degraded.get(task, True)),
            }
        )
    return result


def empty_audio() -> dict:
    """未启用 audio 任务时的固定结构。"""
    return {
        "engine": None,
        "degraded": True,
        "durationSec": 0.0,
        "speechDurationSec": 0.0,
        "silenceRatio": 0.0,
        "segments": [],
        "fullText": "",
        "keywords": [],
    }


def build_report(
    meta,
    sampled_frames: int,
    cfg: ScoringConfig,
    overall: dict,
    segments: List[dict],
    focus_segments: List[dict],
    breaks: List[dict],
    events: List[dict],
    samples: List[TimelineSample],
    audio: Optional[dict],
    algorithms: List[dict],
    warnings: List[str],
    summary: str,
) -> dict:
    """组装最终报告字典。"""
    events.sort(key=lambda e: (e["startSec"], e["type"]))
    return {
        "version": VERSION,
        "video": meta.as_dict(sampled_frames),
        "config": {
            "sampleFps": round(float(cfg.sample_fps), 2),
            "roi": list(cfg.roi) if cfg.roi else [],
            "lang": cfg.lang,
        },
        "algorithms": algorithms,
        "overall": {
            "focusScore": sec(overall["focusScore"]),
            "videoDurationSec": sec(meta.duration_sec),
            "studyDurationSec": sec(overall["studyDurationSec"]),
            "distractedSec": sec(overall["distractedSec"]),
            "absentSec": sec(overall["absentSec"]),
            "phoneSec": sec(overall["phoneSec"]),
            "handPlayingSec": sec(overall["handPlayingSec"]),
            "headDownSec": sec(overall["headDownSec"]),
            "focusRatio": round(float(overall["focusRatio"]), 3),
            "summary": summary,
        },
        "segments": segments,
        "focusSegments": focus_segments,
        "breaks": breaks,
        "events": events,
        "timeline": [sample.to_dict() for sample in samples],
        "audio": audio if audio is not None else empty_audio(),
        "warnings": warnings,
    }


def error_report(message: str, video_path: str = "", warnings: Optional[List[str]] = None) -> dict:
    """运行异常时的兜底 JSON（带 error 字段，结构仍完整）。"""
    return {
        "version": VERSION,
        "video": {
            "path": video_path,
            "durationSec": 0.0,
            "fps": 0.0,
            "width": 0,
            "height": 0,
            "sampledFrames": 0,
        },
        "config": {"sampleFps": 0.0, "roi": [], "lang": "zh"},
        "algorithms": [],
        "overall": {
            "focusScore": 0.0,
            "videoDurationSec": 0.0,
            "studyDurationSec": 0.0,
            "distractedSec": 0.0,
            "absentSec": 0.0,
            "phoneSec": 0.0,
            "handPlayingSec": 0.0,
            "headDownSec": 0.0,
            "focusRatio": 0.0,
            "summary": "分析失败，未能生成有效结果。",
        },
        "segments": [],
        "focusSegments": [],
        "breaks": [],
        "events": [],
        "timeline": [],
        "audio": empty_audio(),
        "warnings": list(warnings or []),
        "error": message,
    }


# ---------------------------------------------------------------------------
# Mock 数据（无需任何模型权重，供 Java 侧联调）
# ---------------------------------------------------------------------------
def make_mock_samples(duration: float, cfg: ScoringConfig, seed: int = 20261020) -> List[TimelineSample]:
    """生成结构完整、时间分布合理的模拟采样点。"""
    rng = random.Random(seed)
    step = 1.0 / max(0.01, cfg.sample_fps)
    duration = max(step, float(duration))
    count = max(1, int(round(duration * cfg.sample_fps)))

    samples: List[TimelineSample] = []
    for index in range(count):
        t = round(index * step, 3)
        ratio = index / max(1, count - 1)
        # 阶段划分：前 55% 专注，15% 走神/玩手，10% 玩手机，8% 离开，其余专注
        if ratio < 0.55:
            state = STATE_FOCUSED
            yaw = rng.uniform(-6.0, 6.0)
            pitch = rng.uniform(-12.0, -2.0)
            score = rng.uniform(80.0, 96.0)
            hands_on_desk = True
            phone = False
            present = True
        elif ratio < 0.70:
            state = STATE_HAND_PLAYING
            yaw = rng.uniform(-15.0, 15.0)
            pitch = rng.uniform(-25.0, -5.0)
            score = rng.uniform(45.0, 62.0)
            hands_on_desk = False
            phone = False
            present = True
        elif ratio < 0.80:
            state = STATE_PHONE
            yaw = rng.uniform(-30.0, -8.0)
            pitch = rng.uniform(-45.0, -25.0)
            score = rng.uniform(20.0, 35.0)
            hands_on_desk = False
            phone = True
            present = True
        elif ratio < 0.88:
            state = STATE_ABSENT
            yaw = 0.0
            pitch = 0.0
            score = 0.0
            hands_on_desk = False
            phone = False
            present = False
        else:
            state = STATE_FOCUSED
            yaw = rng.uniform(-8.0, 8.0)
            pitch = rng.uniform(-18.0, -6.0)
            score = rng.uniform(72.0, 90.0)
            hands_on_desk = True
            phone = False
            present = True

        head_down = present and pitch < -cfg.head_down_pitch
        samples.append(
            TimelineSample(
                sec=t,
                state=state,
                focus_score=round(score, 1),
                head_yaw=round(yaw, 1),
                head_pitch=round(pitch, 1),
                hands_on_desk=hands_on_desk,
                phone=phone,
                person_present=present,
                head_down=head_down,
            )
        )
    return samples


MOCK_TRANSCRIPT = [
    ("我们先来看这道数学题，先读一遍题目。", 1.0, 6.0),
    ("这里要注意单位的换算，先把厘米换成米。", 7.5, 12.0),
    ("很好，这一步算对了，接着做下一题。", 14.0, 18.0),
    ("写作业的时候要坐直，眼睛离本子远一点。", 20.0, 25.0),
    ("这道题不会的话，可以先画个图帮助理解。", 27.0, 32.0),
]


def make_mock_audio(duration: float, cfg: ScoringConfig, enabled: bool) -> dict:
    """生成模拟 ASR 结果；未启用 audio 任务时返回空结构。"""
    if not enabled:
        return empty_audio()

    segments = []
    for text, start, end in MOCK_TRANSCRIPT:
        if start >= duration:
            break
        segments.append(
            {
                "startSec": sec(min(start, duration)),
                "endSec": sec(min(end, duration)),
                "text": text,
            }
        )
    speech = sum(s["endSec"] - s["startSec"] for s in segments)
    if duration > 0:
        silence = max(0.0, 1.0 - speech / duration)
    else:
        silence = 0.0

    keywords = [kw for kw in cfg.audio_keywords if any(kw in s["text"] for s in segments)]
    if not keywords:
        keywords = [kw for kw in ("数学", "作业", "题目") if any(kw in s["text"] for s in segments)]

    return {
        "engine": "mock",
        "degraded": False,
        "durationSec": sec(duration),
        "speechDurationSec": sec(speech),
        "silenceRatio": round(max(0.0, min(1.0, silence)), 2),
        "segments": segments,
        "fullText": "".join(s["text"] for s in segments),
        "keywords": keywords,
    }


def make_mock_events(samples: List[TimelineSample], cfg: ScoringConfig) -> List[dict]:
    """按状态从 mock 采样点派生事件（用于 mock 联调）。"""
    from .distraction import DistractionDetector
    from .hands import HandEventBuilder
    from .phone import PhoneEventBuilder
    from .segmenter import build_leave_events

    events: List[dict] = []
    events.extend(PhoneEventBuilder(cfg).build(samples))
    events.extend(DistractionDetector(cfg).detect(samples))
    events.extend(HandEventBuilder(cfg).build(samples))
    events.extend(build_leave_events(samples, cfg))
    events.sort(key=lambda e: (e["startSec"], e["type"]))
    return events
