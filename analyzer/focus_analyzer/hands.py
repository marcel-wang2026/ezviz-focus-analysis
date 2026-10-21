"""玩手 / 小动作检测。

判定依据: 手部关键点（MediaPipe Hands 或 YOLOv8-pose 的 wrist/elbow）与
桌面区域（ROI）的关系 —— 手离开桌面、频繁移动、手与手机/文具重叠。

参考方案:
- ``google-ai-edge/mediapipe`` Hands
  https://github.com/google-ai-edge/mediapipe
- ``ultralytics/ultralytics`` YOLOv8-pose（wrist/elbow 关键点）
  https://github.com/ultralytics/ultralytics

后端优先级（惰性导入）:
1. MediaPipe Hands（``degraded=False``）
2. OpenCV 皮肤色 + 桌面 ROI 几何规则（``degraded=True``）
"""

from __future__ import annotations

from typing import Any, List, Optional, Tuple

from .scoring import ScoringConfig
from .utils import EVENT_HAND, STATE_HAND_PLAYING, sec, try_import

REF = "ultralytics/ultralytics"
REF_URL = "https://github.com/ultralytics/ultralytics"

# 手指主要关键点（MediaPipe Hands）: 0=wrist, 4=thumb_tip, 8=index_tip, 12=middle_tip
_WRIST = 0
_FINGER_TIPS = (4, 8, 12, 16, 20)


class HandAnalyzer:
    """手部/桌面关系检测器。"""

    def __init__(self, config: ScoringConfig) -> None:
        self.cfg = config
        self.method = "none"
        self.degraded = True
        self._cv2 = try_import("cv2")
        self._np = try_import("numpy")
        self._hands = None
        self._init_backend()

    def _init_backend(self) -> None:
        mp = try_import("mediapipe")
        if mp is not None:
            try:
                self._hands = mp.solutions.hands.Hands(
                    static_image_mode=False,
                    max_num_hands=2,
                    min_detection_confidence=0.5,
                    min_tracking_confidence=0.5,
                )
                self.method = "mediapipe_hands"
                self.degraded = False
            except Exception:  # noqa: BLE001
                self._hands = None
        if self._hands is None and self._cv2 is not None and self._np is not None:
            self.method = "opencv_skin_roi"
            self.degraded = True

    def analyze(
        self, frame: Any, pose_detected: bool
    ) -> Tuple[bool, bool, bool, float]:
        """返回 ``(hands_on_desk, hand_playing, person_present, skin_ratio)``。"""
        if frame is None:
            return False, False, pose_detected, 0.0

        if self._hands is not None:
            result = self._analyze_mediapipe(frame, pose_detected)
            if result is not None:
                return result

        if self._cv2 is not None and self._np is not None:
            return self._analyze_skin_roi(frame, pose_detected)

        return False, False, pose_detected, 0.0

    # -- MediaPipe Hands -----------------------------------------------------
    def _analyze_mediapipe(
        self, frame: Any, pose_detected: bool
    ) -> Optional[Tuple[bool, bool, bool, float]]:
        cv2 = self._cv2
        try:
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            result = self._hands.process(rgb)
        except Exception:  # noqa: BLE001
            self._hands = None
            return None

        height, width = frame.shape[:2]
        desk_line = height * 0.5  # 桌面下沿（ROI 下半部分视为桌面）
        present = bool(result.multi_hand_landmarks) or pose_detected
        if not result.multi_hand_landmarks:
            # 没有检测到手且桌面区域无人体，视为人不在座（由调用方结合头姿判定）
            return False, False, present, 0.0

        on_desk = False
        for hand in result.multi_hand_landmarks.landmark:
            wrist_y = hand[_WRIST].y * height
            tip_on = any(hand[i].y * height >= desk_line for i in _FINGER_TIPS)
            if wrist_y >= desk_line or tip_on:
                on_desk = True
                break
        hand_playing = present and not on_desk
        return on_desk, hand_playing, present, 0.0

    # -- OpenCV 皮肤色 + ROI 兜底 --------------------------------------------
    def _analyze_skin_roi(
        self, frame: Any, pose_detected: bool
    ) -> Tuple[bool, bool, bool, float]:
        cv2 = self._cv2
        np = self._np
        try:
            ycrcb = cv2.cvtColor(frame, cv2.COLOR_BGR2YCrCb)
            lower = np.array([0, 133, 77], dtype=np.uint8)
            upper = np.array([255, 173, 127], dtype=np.uint8)
            mask = cv2.inRange(ycrcb, lower, upper)
            # 只关心桌面下半区域，降低背景干扰
            desk_mask = mask[int(frame.shape[0] * 0.45):, :]
            skin_ratio = float(desk_mask.mean()) / 255.0
        except Exception:  # noqa: BLE001
            return False, False, pose_detected, 0.0

        hands_on_desk = skin_ratio >= self.cfg.skin_min_ratio
        person_present = pose_detected or skin_ratio >= self.cfg.present_min_ratio
        hand_playing = person_present and not hands_on_desk
        return hands_on_desk, hand_playing, person_present, round(skin_ratio, 4)

    def close(self) -> None:
        if self._hands is not None:
            try:
                self._hands.close()
            except Exception:  # noqa: BLE001
                pass


class HandEventBuilder:
    """从时间线提取连续的玩手事件。"""

    def __init__(self, config: ScoringConfig) -> None:
        self.cfg = config

    def build(self, samples: List[Any]) -> List[dict]:
        from .scoring import TimelineSample

        events: List[dict] = []
        run: List[TimelineSample] = []
        step = 1.0 / max(0.01, self.cfg.sample_fps)

        def flush() -> None:
            if not run:
                return
            duration = step if len(run) == 1 else (run[-1].sec - run[0].sec) + step
            if duration + 1e-6 >= self.cfg.hand_min_sec:
                events.append(
                    {
                        "type": EVENT_HAND,
                        "startSec": sec(run[0].sec),
                        "endSec": sec(run[-1].sec),
                        "durationSec": sec(duration),
                        "confidence": round(min(0.9, 0.5 + 0.04 * len(run)), 2),
                        "note": "手离开桌面或频繁小动作",
                    }
                )
            run.clear()

        for sample in samples:
            if sample.state == STATE_HAND_PLAYING:
                run.append(sample)
            else:
                flush()
        flush()
        return events
