"""头部姿态 / 视线方向估计（专注度分析的核心特征）。

参考方案:
- ``yinguobing/head-pose-estimation`` — OpenCV solvePnP + 通用 3D 人脸模型
  https://github.com/yinguobing/head-pose-estimation
- ``deepinsight/insightface`` — 人脸关键点
  https://github.com/deepinsight/insightface
- ``google-ai-edge/mediapipe`` FaceMesh — 468 人脸关键点
  https://github.com/google-ai-edge/mediapipe
- ``ultralytics/ultralytics`` YOLOv8-pose — COCO-17 人体关键点
  https://github.com/ultralytics/ultralytics

后端选择优先级（全部惰性导入）:
1. MediaPipe FaceMesh + solvePnP（精确 yaw/pitch/roll，``degraded=False``）
2. OpenCV Haar 级联（OpenCV 4.x）或 ``cv2.FaceDetectorYN``（OpenCV 5.x + models/yunet.onnx）
   + 几何规则（``degraded=True``）
3. 无可用后端（``detected=False``，``degraded=True``）
"""

from __future__ import annotations

import math
import os
from dataclasses import dataclass
from typing import Any, Optional, Tuple

from .utils import get_logger, try_import

logger = get_logger()

# 通用 3D 人脸模型（yinguobing/head-pose-estimation 使用的标准点，单位 mm）
_MODEL_POINTS = (
    (0.0, 0.0, 0.0),  # 鼻尖
    (0.0, -330.0, -65.0),  # 下巴
    (-225.0, 170.0, -135.0),  # 左眼左角
    (225.0, 170.0, -135.0),  # 右眼右角
    (-150.0, -150.0, -125.0),  # 左嘴角
    (150.0, -150.0, -125.0),  # 右嘴角
)

# MediaPipe FaceMesh 对应的关键点索引
_LANDMARK_INDEX = {
    "nose": 1,
    "chin": 152,
    "left_eye": 33,
    "right_eye": 263,
    "left_mouth": 61,
    "right_mouth": 291,
}

REF = "yinguobing/head-pose-estimation"
REF_URL = "https://github.com/yinguobing/head-pose-estimation"


@dataclass
class HeadPose:
    """单帧头部姿态与视线偏移。

    yaw   : 偏航角，正值 = 向右转头。
    pitch : 俯仰角，正值 = 抬头，负值 = 低头。
    roll  : 翻滚角。
    gaze_yaw / gaze_pitch : 眼球相对眼眶的偏移角（FaceMesh 虹膜关键点），
        与头部角度叠加后作为有效视线方向参与专注度打分。
    """

    yaw: float = 0.0
    pitch: float = 0.0
    roll: float = 0.0
    gaze_yaw: float = 0.0
    gaze_pitch: float = 0.0
    detected: bool = False
    method: str = "none"
    degraded: bool = True
    confidence: float = 0.0
    face_ratio: float = 0.0

    @property
    def effective_yaw(self) -> float:
        """头部 + 视线合成后的有效偏航角。"""
        return self.yaw + 0.5 * self.gaze_yaw

    @property
    def effective_pitch(self) -> float:
        """头部 + 视线合成后的有效俯仰角。"""
        return self.pitch + 0.5 * self.gaze_pitch

    def as_tuple(self) -> Tuple[float, float, float]:
        return (self.yaw, self.pitch, self.roll)


class HeadPoseEstimator:
    """头姿估计器，自动选择可用的后端。"""

    def __init__(
        self,
        roi: Optional[Tuple[float, float, float, float]] = None,
        model_dir: Optional[str] = None,
    ) -> None:
        self.roi = roi
        self.model_dir = model_dir or os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models"
        )
        self.method = "none"
        self.degraded = True
        self._detector_kind = "none"
        self._cv2 = try_import("cv2")
        self._np = try_import("numpy")
        self._face_mesh = None
        self._frontal = None
        self._profile = None
        self._yunet = None
        self._init_backend()

    # -- 初始化 -------------------------------------------------------------
    def _init_backend(self) -> None:
        self._init_opencv_detector()
        self._init_mediapipe()

        if self._face_mesh is not None:
            self.method = "mediapipe_face_mesh+solvepnp"
            self.degraded = False
        elif self._detector_kind == "haar":
            self.method = "opencv_haar_geometry"
            self.degraded = True
        elif self._detector_kind == "yunet":
            self.method = "opencv_yunet_geometry"
            self.degraded = True
        else:
            self.method = "none"
            self.degraded = True

    def _init_opencv_detector(self) -> None:
        cv2 = self._cv2
        if cv2 is None:
            return
        # OpenCV 4.x: Haar 级联
        if hasattr(cv2, "CascadeClassifier"):
            try:
                cascade_dir = cv2.data.haarcascades
                self._frontal = cv2.CascadeClassifier(
                    cascade_dir + "haarcascade_frontalface_default.xml"
                )
                self._profile = cv2.CascadeClassifier(
                    cascade_dir + "haarcascade_profileface.xml"
                )
                if not self._frontal.empty():
                    self._detector_kind = "haar"
                    return
            except Exception as exc:  # noqa: BLE001
                logger.warning("Haar 级联加载失败，尝试 YuNet: %s", exc)
                self._frontal = None
        # OpenCV 5.x: FaceDetectorYN（需 models/yunet.onnx）
        if hasattr(cv2, "FaceDetectorYN_create"):
            yunet_path = os.path.join(self.model_dir, "yunet.onnx")
            if os.path.isfile(yunet_path):
                try:
                    self._yunet = cv2.FaceDetectorYN_create(
                        yunet_path, "", (320, 320), 0.6, 0.3, 1000
                    )
                    self._detector_kind = "yunet"
                    return
                except Exception as exc:  # noqa: BLE001
                    logger.warning("YuNet 加载失败: %s", exc)
                    self._yunet = None
        logger.warning("OpenCV 无可用人脸检测器，头姿降级为不可用")

    def _init_mediapipe(self) -> None:
        mp = try_import("mediapipe")
        if mp is None:
            return
        try:
            self._face_mesh = mp.solutions.face_mesh.FaceMesh(
                static_image_mode=False,
                max_num_faces=1,
                refine_landmarks=True,
                min_detection_confidence=0.5,
                min_tracking_confidence=0.5,
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("MediaPipe FaceMesh 初始化失败，降级几何规则: %s", exc)
            self._face_mesh = None

    # -- 估算 ---------------------------------------------------------------
    def estimate(self, frame: Any) -> HeadPose:
        if frame is None:
            return HeadPose(method=self.method, degraded=self.degraded)
        if self._face_mesh is not None:
            pose = self._estimate_mediapipe(frame)
            if pose is not None:
                return pose
        if self._detector_kind in ("haar", "yunet"):
            return self._estimate_geometry(frame)
        return HeadPose(method="none", degraded=True)

    # -- MediaPipe -----------------------------------------------------------
    def _estimate_mediapipe(self, frame: Any) -> Optional[HeadPose]:
        cv2 = self._cv2
        np = self._np
        try:
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            result = self._face_mesh.process(rgb)
        except Exception as exc:  # noqa: BLE001
            logger.warning("MediaPipe 处理失败，降级 Haar: %s", exc)
            self._face_mesh = None
            return None

        if not result.multi_face_landmarks:
            return HeadPose(detected=False, method=self.method, degraded=False)

        landmarks = result.multi_face_landmarks[0].landmark
        height, width = frame.shape[:2]
        image_points = np.array(
            [
                (landmarks[_LANDMARK_INDEX["nose"]].x * width,
                 landmarks[_LANDMARK_INDEX["nose"]].y * height),
                (landmarks[_LANDMARK_INDEX["chin"]].x * width,
                 landmarks[_LANDMARK_INDEX["chin"]].y * height),
                (landmarks[_LANDMARK_INDEX["left_eye"]].x * width,
                 landmarks[_LANDMARK_INDEX["left_eye"]].y * height),
                (landmarks[_LANDMARK_INDEX["right_eye"]].x * width,
                 landmarks[_LANDMARK_INDEX["right_eye"]].y * height),
                (landmarks[_LANDMARK_INDEX["left_mouth"]].x * width,
                 landmarks[_LANDMARK_INDEX["left_mouth"]].y * height),
                (landmarks[_LANDMARK_INDEX["right_mouth"]].x * width,
                 landmarks[_LANDMARK_INDEX["right_mouth"]].y * height),
            ],
            dtype="double",
        )
        model_points = np.array(_MODEL_POINTS, dtype="double")
        focal = width
        center = (width / 2.0, height / 2.0)
        camera_matrix = np.array(
            [[focal, 0, center[0]], [0, focal, center[1]], [0, 0, 1]],
            dtype="double",
        )
        try:
            ok, rvec, _ = cv2.solvePnP(
                model_points, image_points, camera_matrix, np.zeros((4, 1)),
                flags=cv2.SOLVEPNP_ITERATIVE,
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("solvePnP 失败: %s", exc)
            return HeadPose(detected=False, method=self.method, degraded=False)
        if not ok:
            return HeadPose(detected=False, method=self.method, degraded=False)

        rmat, _ = cv2.Rodrigues(rvec)
        pitch, yaw, roll = _euler_from_rmat(rmat)
        gaze_yaw, gaze_pitch = _estimate_gaze(landmarks, width, height)
        return HeadPose(
            yaw=yaw,
            pitch=pitch,
            roll=roll,
            gaze_yaw=gaze_yaw,
            gaze_pitch=gaze_pitch,
            detected=True,
            method=self.method,
            degraded=False,
            confidence=0.9,
            face_ratio=0.2,
        )

    # -- OpenCV 几何兜底 -----------------------------------------------------
    def _estimate_geometry(self, frame: Any) -> HeadPose:
        box = self._detect_face_box(frame)
        if box is None:
            return HeadPose(detected=False, method=self.method, degraded=True)
        x, y, w, h, is_profile = box
        height, width = frame.shape[:2]
        cx = (x + w / 2.0) / width
        cy = (y + h / 2.0) / height
        if is_profile:
            cue = (cx - 0.5) * 2.0
            yaw = math.copysign(60.0, cue if cue != 0 else 1.0)
        else:
            # 面部中心相对画面中心的水平偏移近似 yaw
            yaw = (cx - 0.5) * 70.0
        # 垂直位置近似 pitch（越低越低头）
        pitch = -((cy - 0.5) * 60.0)
        return HeadPose(
            yaw=yaw,
            pitch=pitch,
            roll=0.0,
            detected=True,
            method=self.method,
            degraded=True,
            confidence=0.4 if is_profile else 0.6,
            face_ratio=(w * h) / float(width * height),
        )

    def _detect_face_box(self, frame: Any) -> Optional[Tuple[int, int, int, int, bool]]:
        cv2 = self._cv2
        try:
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            gray = cv2.equalizeHist(gray)
        except Exception:  # noqa: BLE001
            return None
        height, width = frame.shape[:2]

        if self._detector_kind == "haar":
            faces = self._frontal.detectMultiScale(gray, 1.1, 5, minSize=(60, 60))
            if len(faces) > 0:
                x, y, w, h = faces[0]
                return (int(x), int(y), int(w), int(h), False)
            if self._profile is not None:
                profiles = self._profile.detectMultiScale(gray, 1.1, 5, minSize=(60, 60))
                if len(profiles) > 0:
                    x, y, w, h = profiles[0]
                    return (int(x), int(y), int(w), int(h), True)
            return None

        if self._detector_kind == "yunet":
            try:
                self._yunet.setInputSize((width, height))
                _retval, faces = self._yunet.detect(frame)
            except Exception:  # noqa: BLE001
                return None
            if faces is None or len(faces) == 0:
                return None
            best = max(faces, key=lambda f: float(f[-1]))
            x, y, w, h = int(best[0]), int(best[1]), int(best[2]), int(best[3])
            return (x, y, w, h, False)
        return None

    def close(self) -> None:
        if self._face_mesh is not None:
            try:
                self._face_mesh.close()
            except Exception:  # noqa: BLE001
                pass


# MediaPipe FaceMesh 虹膜关键点索引（refine_landmarks=True 时共 478 点）
_LEFT_EYE_OUTER = 33
_LEFT_EYE_INNER = 133
_RIGHT_EYE_INNER = 362
_RIGHT_EYE_OUTER = 263
_LEFT_IRIS_CENTER = 468
_RIGHT_IRIS_CENTER = 473


def _estimate_gaze(landmarks: Any, width: int, height: int) -> Tuple[float, float]:
    """由 FaceMesh 虹膜/眼眶关键点估计眼球相对眼眶的视线偏移角。

    返回 ``(gaze_yaw, gaze_pitch)``（角度制，度）。关键点不足时返回 ``(0.0, 0.0)``。
    """
    if len(landmarks) <= _RIGHT_IRIS_CENTER:
        return 0.0, 0.0

    left_x = (landmarks[_LEFT_EYE_OUTER].x + landmarks[_LEFT_EYE_INNER].x) / 2.0
    left_y = (landmarks[_LEFT_EYE_OUTER].y + landmarks[_LEFT_EYE_INNER].y) / 2.0
    left_w = abs(landmarks[_LEFT_EYE_OUTER].x - landmarks[_LEFT_EYE_INNER].x)
    right_x = (landmarks[_RIGHT_EYE_OUTER].x + landmarks[_RIGHT_EYE_INNER].x) / 2.0
    right_y = (landmarks[_RIGHT_EYE_OUTER].y + landmarks[_RIGHT_EYE_INNER].y) / 2.0
    right_w = abs(landmarks[_RIGHT_EYE_OUTER].x - landmarks[_RIGHT_EYE_INNER].x)

    if left_w <= 1e-6 or right_w <= 1e-6:
        return 0.0, 0.0

    # 虹膜中心相对眼眶中心的归一化偏移（-0.5..0.5）
    gx_left = (landmarks[_LEFT_IRIS_CENTER].x - left_x) / left_w
    gy_left = (landmarks[_LEFT_IRIS_CENTER].y - left_y) / left_w
    gx_right = (landmarks[_RIGHT_IRIS_CENTER].x - right_x) / right_w
    gy_right = (landmarks[_RIGHT_IRIS_CENTER].y - right_y) / right_w

    # 偏移量映射到角度：眼球最大偏转约 ±30°
    gaze_yaw = ((gx_left + gx_right) / 2.0) * 40.0
    gaze_pitch = ((gy_left + gy_right) / 2.0) * 30.0
    return gaze_yaw, gaze_pitch


def _euler_from_rmat(rmat: Any) -> Tuple[float, float, float]:
    """由旋转矩阵求欧拉角，返回 ``(pitch, yaw, roll)``（角度制）。"""
    sy = math.sqrt(rmat[0, 0] * rmat[0, 0] + rmat[1, 0] * rmat[1, 0])
    if sy > 1e-6:
        pitch = math.atan2(rmat[2, 1], rmat[2, 2])
        yaw = math.atan2(-rmat[2, 0], sy)
        roll = math.atan2(rmat[1, 0], rmat[0, 0])
    else:
        pitch = math.atan2(-rmat[1, 2], rmat[1, 1])
        yaw = math.atan2(-rmat[2, 0], sy)
        roll = 0.0
    return math.degrees(pitch), math.degrees(yaw), math.degrees(roll)
