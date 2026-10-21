"""玩手机检测。

方案: 目标检测（YOLOv8 COCO 预训练 ``cell phone`` 类）+ 与手部区域重叠判定。
参考:
- ``FPI-Det`` / Face–Phone Interaction 数据集论文（人脸-手机交互检测）
- ``ultralytics/ultralytics`` YOLOv8
  https://github.com/ultralytics/ultralytics

后端优先级（惰性导入）:
1. ultralytics YOLOv8（``degraded=False``）
2. OpenCV ``cv2.dnn`` 加载 ``models/yolov8n.onnx``（``degraded=False``）
3. 无模型时，基于桌面 ROI 的矩形亮块 + 手部皮肤重叠启发式（``degraded=True``）
"""

from __future__ import annotations

import os
from typing import Any, List, Optional, Tuple

from .scoring import ScoringConfig
from .utils import EVENT_PHONE, STATE_PHONE, sec, try_import

REF = "ultralytics/ultralytics"
REF_URL = "https://github.com/ultralytics/ultralytics"

# COCO class id: cell phone = 67
_COCO_CELL_PHONE = 67


class PhoneDetector:
    """手机检测器。"""

    def __init__(
        self,
        config: ScoringConfig,
        model_dir: Optional[str] = None,
        weights: Optional[str] = None,
    ) -> None:
        self.cfg = config
        self.model_dir = model_dir or os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models"
        )
        self.weights = weights
        self.method = "none"
        self.degraded = True
        self._cv2 = try_import("cv2")
        self._np = try_import("numpy")
        self._yolo = None
        self._dnn = None
        self._init_backend()

    def _init_backend(self) -> None:
        # 1) ultralytics
        ultralytics = try_import("ultralytics")
        if ultralytics is not None:
            weight_path = self.weights or os.path.join(self.model_dir, "yolov8n.pt")
            if os.path.isfile(weight_path):
                try:
                    self._yolo = ultralytics.YOLO(weight_path)
                    self.method = "yolov8"
                    self.degraded = False
                    return
                except Exception:  # noqa: BLE001
                    self._yolo = None

        # 2) OpenCV DNN onnx
        if self._cv2 is not None and self._np is not None:
            onnx_path = self.weights or os.path.join(self.model_dir, "yolov8n.onnx")
            if onnx_path.endswith(".onnx") and os.path.isfile(onnx_path):
                try:
                    self._dnn = self._cv2.dnn.readNetFromONNX(onnx_path)
                    self.method = "opencv_dnn_yolov8"
                    self.degraded = False
                    return
                except Exception:  # noqa: BLE001
                    self._dnn = None

        # 3) 启发式兜底
        if self._cv2 is not None and self._np is not None:
            self.method = "opencv_heuristic"
            self.degraded = True
        else:
            self.method = "none"
            self.degraded = True

    def detect(self, frame: Any) -> Tuple[bool, float]:
        """返回 ``(phone_detected, confidence)``。"""
        if frame is None:
            return False, 0.0
        if self._yolo is not None:
            return self._detect_yolo(frame)
        if self._dnn is not None:
            return self._detect_dnn(frame)
        if self._cv2 is not None:
            return self._detect_heuristic(frame)
        return False, 0.0

    # -- ultralytics ---------------------------------------------------------
    def _detect_yolo(self, frame: Any) -> Tuple[bool, float]:
        try:
            results = self._yolo.predict(frame, verbose=False, conf=0.35)
        except Exception:  # noqa: BLE001
            return False, 0.0
        best = 0.0
        for result in results:
            boxes = getattr(result, "boxes", None)
            if boxes is None:
                continue
            for box in boxes:
                cls_id = int(box.cls[0]) if box.cls is not None else -1
                if cls_id == _COCO_CELL_PHONE:
                    best = max(best, float(box.conf[0]))
        return best > 0, round(best, 2)

    # -- OpenCV DNN ----------------------------------------------------------
    def _detect_dnn(self, frame: Any) -> Tuple[bool, float]:
        cv2 = self._cv2
        np = self._np
        try:
            height, width = frame.shape[:2]
            blob = cv2.dnn.blobFromImage(
                frame,
                scalefactor=1 / 255.0,
                size=(640, 640),
                swapRB=True,
                crop=False,
            )
            self._dnn.setInput(blob)
            outputs = self._dnn.forward()
            predictions = np.squeeze(outputs)
            if predictions.ndim != 2:
                return False, 0.0
            if predictions.shape[0] < predictions.shape[1]:
                predictions = predictions.T
            best = 0.0
            for row in predictions:
                if row.shape[0] < 6:
                    continue
                scores = row[4:]
                cls_id = int(np.argmax(scores))
                conf = float(scores[cls_id])
                if cls_id == _COCO_CELL_PHONE and conf > 0.35:
                    best = max(best, conf)
            return best > 0, round(best, 2)
        except Exception:  # noqa: BLE001
            return False, 0.0

    # -- 启发式兜底 ----------------------------------------------------------
    def _detect_heuristic(self, frame: Any) -> Tuple[bool, float]:
        """在桌面下半区域寻找高亮/深色矩形块，并要求邻近手部皮肤。

        该规则只用于无模型时的联调，召回率有限，``degraded=True``。
        """
        cv2 = self._cv2
        np = self._np
        try:
            height, width = frame.shape[:2]
            y0 = int(height * 0.45)
            desk = frame[y0:, :]
            if desk.size == 0:
                return False, 0.0

            hsv = cv2.cvtColor(desk, cv2.COLOR_BGR2HSV)
            gray = cv2.cvtColor(desk, cv2.COLOR_BGR2GRAY)
            # 手机屏幕通常亮且低饱和，或纯黑机身且边缘规整
            bright = cv2.inRange(hsv, np.array([0, 0, 190]), np.array([180, 60, 255]))
            dark = cv2.inRange(gray, 0, 40)
            mask = cv2.bitwise_or(bright, dark)
            mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
            contours, _ = cv2.findContours(
                mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE
            )

            ycrcb = cv2.cvtColor(desk, cv2.COLOR_BGR2YCrCb)
            skin = cv2.inRange(
                ycrcb, np.array([0, 133, 77]), np.array([255, 173, 127])
            )

            max_area = 0.06 * desk.shape[0] * desk.shape[1]
            for contour in contours:
                area = cv2.contourArea(contour)
                if area < 400 or area > max_area:
                    continue
                x, y, w, h = cv2.boundingRect(contour)
                if h <= 0:
                    continue
                aspect = w / float(h)
                if not (0.4 <= aspect <= 2.5):
                    continue
                # 手机附近应有手（皮肤像素）
                pad = int(max(w, h) * 0.5)
                near = skin[
                    max(0, y - pad): min(skin.shape[0], y + h + pad),
                    max(0, x - pad): min(skin.shape[1], x + w + pad),
                ]
                if near.size == 0 or float(near.mean()) / 255.0 < 0.02:
                    continue
                return True, 0.45
            return False, 0.0
        except Exception:  # noqa: BLE001
            return False, 0.0


class PhoneEventBuilder:
    """从时间线提取连续的手机使用事件。"""

    def __init__(self, config: ScoringConfig) -> None:
        self.cfg = config

    def build(self, samples: List[Any]) -> List[dict]:
        events: List[dict] = []
        run: List[Any] = []
        step = 1.0 / max(0.01, self.cfg.sample_fps)

        def flush() -> None:
            if not run:
                return
            duration = step if len(run) == 1 else (run[-1].sec - run[0].sec) + step
            if duration + 1e-6 >= self.cfg.phone_min_sec:
                events.append(
                    {
                        "type": EVENT_PHONE,
                        "startSec": sec(run[0].sec),
                        "endSec": sec(run[-1].sec),
                        "durationSec": sec(duration),
                        "confidence": round(min(0.95, 0.6 + 0.03 * len(run)), 2),
                        "note": "检测到手机",
                    }
                )
            run.clear()

        for sample in samples:
            if sample.state == STATE_PHONE:
                run.append(sample)
            else:
                flush()
        flush()
        return events
