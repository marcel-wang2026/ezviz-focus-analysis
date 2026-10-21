"""通用工具函数：日志、惰性导入、数值与 ROI 解析。"""

from __future__ import annotations

import importlib
import logging
import sys
from typing import Any, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# 状态与事件枚举（严格遵循 JSON 契约）
# ---------------------------------------------------------------------------
STATE_FOCUSED = "FOCUSED"
STATE_DISTRACTED = "DISTRACTED"
STATE_PHONE = "PHONE"
STATE_ABSENT = "ABSENT"
STATE_HAND_PLAYING = "HAND_PLAYING"
STATE_LEVELS: Tuple[str, ...] = (
    STATE_FOCUSED,
    STATE_DISTRACTED,
    STATE_PHONE,
    STATE_ABSENT,
    STATE_HAND_PLAYING,
)

EVENT_PHONE = "PHONE_USE"
EVENT_DISTRACTION = "DISTRACTION"
EVENT_HAND = "HAND_PLAYING"
EVENT_LEAVE = "LEAVE_SEAT"
EVENT_TYPES: Tuple[str, ...] = (
    EVENT_PHONE,
    EVENT_DISTRACTION,
    EVENT_HAND,
    EVENT_LEAVE,
)

_LOGGER: Optional[logging.Logger] = None


def get_logger() -> logging.Logger:
    """返回包内共享的 logger，日志只输出到 stderr。"""
    global _LOGGER
    if _LOGGER is None:
        logger = logging.getLogger("focus_analyzer")
        logger.setLevel(logging.INFO)
        if not logger.handlers:
            handler = logging.StreamHandler(sys.stderr)
            handler.setFormatter(logging.Formatter("[%(levelname)s] %(message)s"))
            logger.addHandler(handler)
        logger.propagate = False
        _LOGGER = logger
    return _LOGGER


def try_import(module_name: str) -> Any:
    """惰性导入可选的重型依赖，缺失时返回 ``None`` 而不是抛异常。"""
    try:
        return importlib.import_module(module_name)
    except Exception:  # noqa: BLE001 - 任何导入失败都视为不可用
        return None


def sec(value: Any, ndigits: int = 1) -> float:
    """转换为秒的浮点数，异常时返回 0.0（保证 JSON 无 null）。"""
    try:
        return round(float(value), ndigits)
    except (TypeError, ValueError):
        return 0.0


def num(value: Any, default: float = 0.0) -> float:
    """转换为浮点数，异常时返回默认值。"""
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def clamp(value: float, low: float, high: float) -> float:
    """将数值限制在 [low, high]。"""
    return max(low, min(high, value))


def mean(values: Sequence[float], default: float = 0.0) -> float:
    """安全求均值，空序列返回默认值。"""
    values = [float(v) for v in values]
    if not values:
        return default
    return sum(values) / len(values)


def parse_roi(raw: Any) -> Optional[Tuple[float, float, float, float]]:
    """解析归一化 ROI（x,y,w,h）。

    支持字符串 ``"0.2,0.1,0.6,0.8"`` 或序列 ``[0.2, 0.1, 0.6, 0.8]``；
    传入 ``None`` 时返回 ``None``（表示整帧）。
    """
    if raw is None:
        return None
    if isinstance(raw, str):
        parts: List[str] = [p for p in raw.replace(" ", "").split(",") if p != ""]
    elif isinstance(raw, (list, tuple)):
        parts = list(raw)
    else:
        raise ValueError("ROI 格式非法，应为 'x,y,w,h' 或 [x,y,w,h]")
    if len(parts) != 4:
        raise ValueError("ROI 需要恰好 4 个归一化数值 (x,y,w,h)")
    x, y, w, h = (float(p) for p in parts)
    for name, v in (("x", x), ("y", y), ("w", w), ("h", h)):
        if v < 0.0 or v > 1.0:
            raise ValueError(f"ROI 分量 {name}={v} 超出归一化范围 [0,1]")
    if x + w > 1.0 or y + h > 1.0:
        raise ValueError("ROI 的 x+w / y+h 不能超过 1.0")
    return (x, y, w, h)


def roi_to_pixels(
    roi: Optional[Tuple[float, float, float, float]],
    width: int,
    height: int,
) -> Optional[Tuple[int, int, int, int]]:
    """将归一化 ROI 转为像素矩形 (x, y, w, h)，ROI 为空返回 ``None``。"""
    if roi is None:
        return None
    x, y, w, h = roi
    px = int(round(x * width))
    py = int(round(y * height))
    pw = max(1, int(round(w * width)))
    ph = max(1, int(round(h * height)))
    # 防止越界
    px = max(0, min(px, width - 1))
    py = max(0, min(py, height - 1))
    pw = min(pw, width - px)
    ph = min(ph, height - py)
    return (px, py, pw, ph)


def crop_frame(frame: Any, rect: Optional[Tuple[int, int, int, int]]) -> Any:
    """按像素矩形裁剪帧；rect 为空则原样返回。"""
    if rect is None or frame is None:
        return frame
    x, y, w, h = rect
    return frame[y:y + h, x:x + w]
