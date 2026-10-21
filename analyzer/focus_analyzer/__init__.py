"""ezviz-focus-analysis 音频/视频分析算法包。

模块划分::

    video_reader   抽帧与元信息
    head_pose      头部姿态/视线方向
    focus          专注度打分与状态机
    distraction    走神（注意力涣散）检测
    hands          玩手/小动作检测
    phone          玩手机检测
    audio_asr      音频转文本
    segmenter      学习时间段 / 休息间隔切分
    scoring        打分权重与阈值（可配置）
    report         组装最终 JSON
    utils          通用工具

所有重型依赖（ultralytics、mediapipe、faster-whisper/torch）均为惰性导入，
缺失时会自动降级到 OpenCV + 几何规则的兜底实现，并在报告中标记 degraded。
"""

from .utils import (
    STATE_ABSENT,
    STATE_DISTRACTED,
    STATE_FOCUSED,
    STATE_HAND_PLAYING,
    STATE_PHONE,
)

__version__ = "1.0"

__all__ = [
    "STATE_FOCUSED",
    "STATE_DISTRACTED",
    "STATE_PHONE",
    "STATE_ABSENT",
    "STATE_HAND_PLAYING",
]
