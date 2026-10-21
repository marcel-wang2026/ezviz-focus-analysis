"""专注度打分与状态机。

状态枚举（严格遵循 JSON 契约）:
``FOCUSED`` / ``DISTRACTED`` / ``PHONE`` / ``ABSENT`` / ``HAND_PLAYING``。

状态机依据每个采样点的头姿与检测结果判定状态，再据此给出 0-100 的专注度分。
参考课堂专注度行为识别类项目（YOLO-Pose + 几何规则）与
``classroom-behavior-recognition`` (https://github.com/search?q=classroom+behavior+recognition)。
"""

from __future__ import annotations

from typing import Any, Optional

from .head_pose import HeadPose
from .scoring import ScoringConfig
from .utils import (
    STATE_ABSENT,
    STATE_DISTRACTED,
    STATE_FOCUSED,
    STATE_HAND_PLAYING,
    STATE_PHONE,
    clamp,
)


class FocusStateMachine:
    """逐采样点判定专注状态并打分。"""

    def __init__(self, config: ScoringConfig) -> None:
        self.cfg = config

    def state(
        self,
        pose: HeadPose,
        person_present: bool,
        hands_on_desk: bool,
        hand_playing: bool,
        phone: bool,
    ) -> str:
        """判定单个采样点的状态（头姿与视线方向合成后参与判定）。"""
        if not person_present and not pose.detected:
            return STATE_ABSENT
        if phone:
            return STATE_PHONE
        if hand_playing:
            return STATE_HAND_PLAYING
        if pose.detected:
            if abs(pose.effective_yaw) > self.cfg.yaw_max:
                return STATE_DISTRACTED
            if pose.effective_pitch < -self.cfg.head_down_pitch:
                return STATE_DISTRACTED
        return STATE_FOCUSED

    def score(self, state: str, pose: HeadPose) -> float:
        """给出 0-100 的专注度分。"""
        if state == STATE_ABSENT:
            return 0.0
        if state == STATE_PHONE:
            base = self.cfg.phone_penalty
        elif state == STATE_HAND_PLAYING:
            base = self.cfg.hand_penalty
        else:
            base = 100.0
        if pose.detected:
            yaw_ratio = min(1.0, abs(pose.effective_yaw) / max(1.0, self.cfg.yaw_max))
            down = max(0.0, -pose.effective_pitch)
            pitch_ratio = min(1.0, down / max(1.0, self.cfg.head_down_pitch))
            deviation = max(yaw_ratio, pitch_ratio)
            base = min(base, 100.0 - self.cfg.max_deviation_penalty * deviation)
        elif state == STATE_FOCUSED:
            # 头姿不可用时仅依赖在座/手部规则，置信度较低，封顶 85 分
            base = min(base, 85.0)
        if state == STATE_DISTRACTED:
            base = min(base, self.cfg.max_distraction_score)
        return round(clamp(base, 0.0, 100.0), 1)

    def head_down(self, pose: HeadPose) -> bool:
        return pose.detected and pose.effective_pitch < -self.cfg.head_down_pitch
