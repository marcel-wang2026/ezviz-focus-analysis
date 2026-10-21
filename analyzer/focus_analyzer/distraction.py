"""走神（注意力涣散）检测。

走神判定: 连续一段时间内头部偏离桌面正前方、长时间低头或长时间静止不动。
参考课堂专注度研究论文与 ``classroom-behavior-recognition`` 类项目
（YOLO-Pose 关键点 + 几何规则判定课堂行为）。

本模块从逐采样时间线里提取连续的 ``DISTRACTED`` 片段，合并为
``DISTRACTION`` 事件并给出置信度。
"""

from __future__ import annotations

from typing import List

from .scoring import ScoringConfig, TimelineSample
from .utils import EVENT_DISTRACTION, STATE_DISTRACTED, sec


class DistractionDetector:
    """从时间线提取走神事件。"""

    def __init__(self, config: ScoringConfig) -> None:
        self.cfg = config

    def detect(self, samples: List[TimelineSample]) -> List[dict]:
        """返回 ``DISTRACTION`` 事件列表。"""
        events: List[dict] = []
        run: List[TimelineSample] = []

        def flush() -> None:
            if not run:
                return
            # 时长基于采样间隔估算
            duration = _run_duration(run, self.cfg.sample_fps)
            if duration + 1e-6 >= self.cfg.distraction_min_sec:
                deviation = _avg_deviation(run, self.cfg)
                confidence = round(min(0.95, 0.5 + 0.45 * deviation), 2)
                events.append(
                    {
                        "type": EVENT_DISTRACTION,
                        "startSec": sec(run[0].sec),
                        "endSec": sec(run[-1].sec),
                        "durationSec": sec(duration),
                        "confidence": confidence,
                        "note": _note(run, self.cfg),
                    }
                )
            run.clear()

        for sample in samples:
            if sample.state == STATE_DISTRACTED:
                run.append(sample)
            else:
                flush()
        flush()
        return events


def _run_duration(run: List[TimelineSample], sample_fps: float) -> float:
    step = 1.0 / max(0.01, sample_fps)
    if len(run) == 1:
        return step
    return (run[-1].sec - run[0].sec) + step


def _avg_deviation(run: List[TimelineSample], cfg: ScoringConfig) -> float:
    if not run:
        return 0.0
    total = 0.0
    for sample in run:
        yaw_ratio = min(1.0, abs(sample.head_yaw) / max(1.0, cfg.yaw_max))
        pitch_ratio = min(1.0, max(0.0, -sample.head_pitch) / max(1.0, cfg.head_down_pitch))
        total += max(yaw_ratio, pitch_ratio)
    return total / len(run)


def _note(run: List[TimelineSample], cfg: ScoringConfig) -> str:
    if not run:
        return "走神"
    avg_pitch = sum(s.head_pitch for s in run) / len(run)
    if avg_pitch < -cfg.head_down_pitch:
        return "持续低头"
    return "持续转头"
