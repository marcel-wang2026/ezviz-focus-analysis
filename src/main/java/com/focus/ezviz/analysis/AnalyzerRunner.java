package com.focus.ezviz.analysis;

import com.fasterxml.jackson.databind.JsonNode;

/** 视频特征分析执行器：命令行子进程或独立 HTTP 算法服务 */
public interface AnalyzerRunner {

    /**
     * @param videoFile 视频绝对路径
     * @param roi       归一化 ROI，可为空
     * @param tasks     逗号分隔的算法任务
     * @return 算法原始 JSON 结果
     */
    JsonNode analyze(String videoFile, String roi, String tasks);
}
