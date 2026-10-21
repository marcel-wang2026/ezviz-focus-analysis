"""打分权重、阈值与逐帧采样记录。

``ScoringConfig`` 集中管理所有可调阈值与权重（可由 ``--config`` 载入），
``TimelineSample`` 是逐采样帧的分析结果，也是时间线 ``timeline`` 数组的元素。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from .utils import (
    STATE_ABSENT,
    STATE_FOCUSED,
    get_logger,
    num,
    parse_roi,
    sec,
)

logger = get_logger()


@dataclass
class ScoringConfig:
    """阈值与权重配置。"""

    sample_fps: float = 1.0
    roi: Optional[Tuple[float, float, float, float]] = None
    lang: str = "zh"

    # 阈值
    yaw_max: float = 20.0          # |yaw| 超过该值判为转头
    head_down_pitch: float = 20.0   # pitch 低于 -该值判为低头
    distraction_min_sec: float = 5.0
    phone_min_sec: float = 3.0
    hand_min_sec: float = 3.0
    absent_min_sec: float = 5.0
    focus_min_sec: float = 30.0
    segment_sec: float = 300.0

    # 归一化阈值（兜底几何规则使用）
    skin_min_ratio: float = 0.02      # 桌面 ROI 内皮肤占比达到该值视为手在桌面
    present_min_ratio: float = 0.008    # 皮肤占比达到该值视为人在座

    # 权重
    phone_penalty: float = 25.0
    hand_penalty: float = 55.0
    max_distraction_score: float = 60.0
    max_deviation_penalty: float = 45.0

    # 音频
    audio_keywords: List[str] = field(default_factory=list)
    audio_model_size: str = "small"

    # 元信息
    source: str = "default"

    @classmethod
    def from_dict(cls, data: Optional[Dict[str, Any]]) -> "ScoringConfig":
        cfg = cls()
        if not data:
            return cfg
        cfg.source = "config"
        if "sampleFps" in data:
            cfg.sample_fps = max(0.01, num(data.get("sampleFps"), cfg.sample_fps))
        if "roi" in data and data.get("roi") is not None:
            cfg.roi = parse_roi(data.get("roi"))
        if "lang" in data and data.get("lang"):
            cfg.lang = str(data.get("lang"))

        thresholds = data.get("thresholds") or {}
        weights = data.get("weights") or {}
        audio = data.get("audio") or {}

        cfg.yaw_max = num(thresholds.get("yawMax"), cfg.yaw_max)
        cfg.head_down_pitch = num(thresholds.get("headDownPitch"), cfg.head_down_pitch)
        cfg.distraction_min_sec = num(
            thresholds.get("distractionMinSec"), cfg.distraction_min_sec
        )
        cfg.phone_min_sec = num(thresholds.get("phoneMinSec"), cfg.phone_min_sec)
        cfg.hand_min_sec = num(thresholds.get("handMinSec"), cfg.hand_min_sec)
        cfg.absent_min_sec = num(thresholds.get("absentMinSec"), cfg.absent_min_sec)
        cfg.focus_min_sec = num(thresholds.get("focusMinSec"), cfg.focus_min_sec)
        cfg.segment_sec = num(thresholds.get("segmentSec"), cfg.segment_sec)
        cfg.skin_min_ratio = num(thresholds.get("skinMinRatio"), cfg.skin_min_ratio)
        cfg.present_min_ratio = num(
            thresholds.get("presentMinRatio"), cfg.present_min_ratio
        )

        cfg.phone_penalty = num(weights.get("phonePenalty"), cfg.phone_penalty)
        cfg.hand_penalty = num(weights.get("handPenalty"), cfg.hand_penalty)
        cfg.max_distraction_score = num(
            weights.get("maxDistractionScore"), cfg.max_distraction_score
        )
        cfg.max_deviation_penalty = num(
            weights.get("maxDeviationPenalty"), cfg.max_deviation_penalty
        )

        if audio.get("keywords"):
            cfg.audio_keywords = [str(k) for k in audio.get("keywords", [])]
        if audio.get("modelSize"):
            cfg.audio_model_size = str(audio["modelSize"])
        return cfg

    def to_dict(self) -> dict:
        return {
            "sampleFps": cfg_float(self.sample_fps),
            "roi": list(self.roi) if self.roi else [],
            "lang": self.lang,
        }


@dataclass
class TimelineSample:
    """单个采样点的分析结果，对应 ``timeline`` 数组元素。"""

    sec: float
    state: str = STATE_FOCUSED
    focus_score: float = 0.0
    head_yaw: float = 0.0
    head_pitch: float = 0.0
    hands_on_desk: bool = False
    phone: bool = False
    person_present: bool = True
    head_down: bool = False

    def to_dict(self) -> dict:
        return {
            "sec": int(round(self.sec)),
            "state": self.state,
            "focusScore": sec(self.focus_score),
            "headYaw": sec(self.head_yaw),
            "headPitch": sec(self.head_pitch),
            "handsOnDesk": bool(self.hands_on_desk),
            "phone": bool(self.phone),
        }


def cfg_float(value: Any, default: float = 0.0) -> float:
    return round(num(value, default), 2)
