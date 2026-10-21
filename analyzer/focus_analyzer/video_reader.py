"""视频元信息探测与抽帧。

优先使用 ``ffprobe`` 读取时长/帧率/分辨率（对手机、摄像头转码的 MP4 更可靠），
缺失时回退到 OpenCV ``cv2.VideoCapture``。抽帧按 ``sample_fps`` 固定间隔进行，
避免整段视频逐帧解码。
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from dataclasses import dataclass
from typing import Any, Iterator, Optional, Tuple

from .utils import crop_frame, get_logger, roi_to_pixels, try_import

logger = get_logger()


@dataclass
class VideoMeta:
    """视频基础元信息。"""

    path: str
    duration_sec: float = 0.0
    fps: float = 0.0
    width: int = 0
    height: int = 0
    has_audio: bool = False

    def as_dict(self, sampled_frames: int) -> dict:
        return {
            "path": self.path,
            "durationSec": round(float(self.duration_sec), 1),
            "fps": round(float(self.fps), 1),
            "width": int(self.width),
            "height": int(self.height),
            "sampledFrames": int(sampled_frames),
        }


class VideoReadError(RuntimeError):
    """视频无法读取时抛出。"""


def _ffprobe_json(path: str) -> Optional[dict]:
    """调用 ffprobe 获取流信息，失败返回 None。"""
    if shutil.which("ffprobe") is None:
        return None
    cmd = [
        "ffprobe",
        "-v",
        "error",
        "-print_format",
        "json",
        "-show_format",
        "-show_streams",
        path,
    ]
    try:
        proc = subprocess.run(
            cmd, capture_output=True, text=True, timeout=60, check=False
        )
        if proc.returncode != 0:
            return None
        return json.loads(proc.stdout or "{}")
    except Exception as exc:  # noqa: BLE001
        logger.warning("ffprobe 探测失败，回退 OpenCV: %s", exc)
        return None


def _meta_from_ffprobe(path: str, info: dict) -> Optional[VideoMeta]:
    video_stream = None
    has_audio = False
    for stream in info.get("streams", []) or []:
        if stream.get("codec_type") == "video" and video_stream is None:
            video_stream = stream
        elif stream.get("codec_type") == "audio":
            has_audio = True
    if video_stream is None:
        return None

    duration = 0.0
    fmt = info.get("format") or {}
    try:
        duration = float(fmt.get("duration") or 0.0)
    except (TypeError, ValueError):
        duration = 0.0
    if duration <= 0:
        try:
            duration = float(video_stream.get("duration") or 0.0)
        except (TypeError, ValueError):
            duration = 0.0

    fps = 0.0
    rate = video_stream.get("avg_frame_rate") or video_stream.get("r_frame_rate") or "0/1"
    try:
        if isinstance(rate, str) and "/" in rate:
            num, den = rate.split("/", 1)
            fps = float(num) / float(den) if float(den) else 0.0
        else:
            fps = float(rate)
    except (TypeError, ValueError, ZeroDivisionError):
        fps = 0.0

    return VideoMeta(
        path=path,
        duration_sec=duration,
        fps=fps,
        width=int(video_stream.get("width") or 0),
        height=int(video_stream.get("height") or 0),
        has_audio=has_audio,
    )


def _meta_from_opencv(path: str) -> VideoMeta:
    cv2 = try_import("cv2")
    if cv2 is None:
        raise VideoReadError("缺少 opencv，且 ffprobe 不可用，无法读取视频")

    capture = cv2.VideoCapture(path)
    if not capture.isOpened():
        capture.release()
        raise VideoReadError(f"无法打开视频: {path}")
    try:
        fps = float(capture.get(cv2.CAP_PROP_FPS) or 0.0)
        frame_count = float(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0.0)
        width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
        height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
        duration = frame_count / fps if fps > 0 else 0.0
    finally:
        capture.release()

    return VideoMeta(
        path=path,
        duration_sec=duration,
        fps=fps,
        width=width,
        height=height,
        has_audio=False,
    )


def probe_video(path: str) -> VideoMeta:
    """探测视频元信息，优先 ffprobe，失败回退 OpenCV。"""
    if not os.path.isfile(path):
        raise VideoReadError(f"视频文件不存在: {path}")

    info = _ffprobe_json(path)
    if info:
        meta = _meta_from_ffprobe(path, info)
        if meta is not None and meta.width > 0 and meta.height > 0:
            if meta.duration_sec <= 0 or meta.fps <= 0:
                fallback = _meta_from_opencv(path)
                meta.duration_sec = meta.duration_sec or fallback.duration_sec
                meta.fps = meta.fps or fallback.fps
            return meta

    return _meta_from_opencv(path)


class VideoReader:
    """按 ``sample_fps`` 抽帧的轻量读取器。"""

    def __init__(
        self,
        path: str,
        sample_fps: float = 1.0,
        roi: Optional[Tuple[float, float, float, float]] = None,
        max_width: int = 960,
    ) -> None:
        self.path = path
        self.sample_fps = max(0.01, float(sample_fps))
        self.roi = roi
        self.max_width = int(max_width)
        self.meta: VideoMeta = probe_video(path)
        self._crop_rect = roi_to_pixels(roi, self.meta.width, self.meta.height)
        self._resize_scale = 1.0
        if self.meta.width > self.max_width > 0:
            self._resize_scale = self.max_width / float(self.meta.width)

    # -- 抽帧 ---------------------------------------------------------------
    def iter_frames(self) -> Iterator[Tuple[float, Any]]:
        """逐帧产出 ``(时间秒, BGR 帧)``，帧已按 ROI 裁剪并缩放到分析尺寸。"""
        cv2 = try_import("cv2")
        if cv2 is None:
            raise VideoReadError("缺少 opencv，无法抽帧")

        capture = cv2.VideoCapture(self.path)
        if not capture.isOpened():
            capture.release()
            raise VideoReadError(f"无法打开视频: {self.path}")

        native_fps = self.meta.fps if self.meta.fps > 0 else 25.0
        step = max(1, int(round(native_fps / self.sample_fps)))
        try:
            frame_index = 0
            while True:
                ok = capture.grab()
                if not ok:
                    break
                if frame_index % step != 0:
                    frame_index += 1
                    continue
                ok, frame = capture.retrieve()
                if not ok or frame is None:
                    frame_index += 1
                    continue
                timestamp = frame_index / native_fps if native_fps > 0 else 0.0
                frame = crop_frame(frame, self._crop_rect)
                if self._resize_scale < 1.0:
                    frame = cv2.resize(
                        frame,
                        (
                            max(1, int(frame.shape[1] * self._resize_scale)),
                            max(1, int(frame.shape[0] * self._resize_scale)),
                        ),
                        interpolation=cv2.INTER_AREA,
                    )
                yield timestamp, frame
                frame_index += 1
        finally:
            capture.release()

    def expected_sample_count(self) -> int:
        """预估抽帧数量（用于 mock 与进度统计）。"""
        duration = self.meta.duration_sec
        if duration <= 0:
            duration = 60.0
        return max(1, int(round(duration * self.sample_fps)))


def extract_audio(video_path: str, wav_path: str) -> bool:
    """用 ffmpeg 抽取单声道 16k PCM 音频，供 VAD/ASR 使用。"""
    if shutil.which("ffmpeg") is None:
        logger.warning("未找到 ffmpeg，无法抽取音频")
        return False
    cmd = [
        "ffmpeg",
        "-y",
        "-v",
        "error",
        "-i",
        video_path,
        "-vn",
        "-ac",
        "1",
        "-ar",
        "16000",
        "-f",
        "wav",
        wav_path,
    ]
    try:
        proc = subprocess.run(
            cmd, capture_output=True, text=True, timeout=1800, check=False
        )
        if proc.returncode != 0 or not os.path.isfile(wav_path):
            logger.warning("ffmpeg 抽取音频失败: %s", (proc.stderr or "").strip()[:300])
            return False
        return os.path.getsize(wav_path) > 1024
    except Exception as exc:  # noqa: BLE001
        logger.warning("ffmpeg 抽取音频异常: %s", exc)
        return False
