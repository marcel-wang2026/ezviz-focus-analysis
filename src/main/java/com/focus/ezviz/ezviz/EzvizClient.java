package com.focus.ezviz.ezviz;

import com.fasterxml.jackson.databind.JsonNode;
import com.focus.ezviz.domain.EzvizAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 萤石开放平台客户端。
 *
 * <ul>
 *   <li>accessToken：POST /api/lapp/token/get</li>
 *   <li>设备列表：POST /api/lapp/device/list</li>
 *   <li>录像日历：GET /api/v3/open/cloud/video/exist/query/month</li>
 *   <li>录像分段：POST /api/lapp/video/by/time（recType 1=云存储 2=本地录像）</li>
 *   <li>HLS 点播地址：POST /api/service/open/vod/file/address/hls</li>
 * </ul>
 *
 * <p>说明：不同应用权限（个人版/企业版）可用的接口可能不同，调用失败时会在日志与页面提示具体返回码。</p>
 */
@Component
public class EzvizClient {

    private static final Logger log = LoggerFactory.getLogger(EzvizClient.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EzvizTokenService tokenService;

    public EzvizClient(EzvizTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /** 萤石设备 */
    public record EzvizDevice(String deviceSerial, String deviceName, int channelNo, String status) {
    }

    /** 一段可下载的录像时间范围；url 为部分接口直接返回的播放地址，可能为空 */
    public record RecordingSpan(LocalDateTime begin, LocalDateTime end, String url) {
        public RecordingSpan(LocalDateTime begin, LocalDateTime end) {
            this(begin, end, null);
        }
    }

    public List<EzvizDevice> listDevices(EzvizAccount account) {
        String token = tokenService.getToken(account, false);
        Map<String, Object> form = new HashMap<>();
        form.put("accessToken", token);
        form.put("pageStart", 0);
        form.put("pageSize", 50);
        JsonNode root = tokenService.postForm("/api/lapp/device/list", null, form, "获取设备列表失败");
        List<EzvizDevice> devices = new ArrayList<>();
        for (JsonNode node : root.path("data")) {
            String serial = node.path("deviceSerial").asText("");
            if (serial.isBlank()) {
                continue;
            }
            int channel = node.path("channelNo").asInt(1);
            devices.add(new EzvizDevice(serial, node.path("deviceName").asText(serial),
                    Math.max(channel, 1), node.path("status").asText("")));
        }
        return devices;
    }

    /** 查询某月哪些天有云存储录像，返回 yyyy-MM-dd 列表 */
    public List<String> listCloudDays(EzvizAccount account, String deviceSerial, int channelNo, String yearMonth) {
        String token = tokenService.getToken(account, false);
        String path = "/api/v3/open/cloud/video/exist/query/month?accessToken=" + HttpSupport.urlEncode(token)
                + "&deviceSerial=" + HttpSupport.urlEncode(deviceSerial)
                + "&channelNo=" + channelNo
                + "&queryMonth=" + HttpSupport.urlEncode(yearMonth)
                + "&recType=1";
        JsonNode root = tokenService.get(path, null, "查询录像日历失败");
        List<String> days = new ArrayList<>();
        for (JsonNode node : root.path("data")) {
            String day = node.path("recDay").asText("");
            if (!day.isBlank()) {
                days.add(day);
            }
        }
        return days;
    }

    /**
     * 查询指定时间范围内的录像分段。
     *
     * @param recType 1=云存储，2=本地录像（SD 卡）
     */
    public List<RecordingSpan> listRecordingSpans(EzvizAccount account, String deviceSerial, int channelNo,
                                                  LocalDateTime from, LocalDateTime to, int recType) {
        String token = tokenService.getToken(account, false);
        Map<String, Object> form = new HashMap<>();
        form.put("accessToken", token);
        form.put("deviceSerial", deviceSerial);
        form.put("channelNo", channelNo);
        form.put("startTime", from.atZone(ZONE).toEpochSecond());
        form.put("endTime", to.atZone(ZONE).toEpochSecond());
        form.put("recType", recType);
        JsonNode root = tokenService.postForm("/api/lapp/video/by/time", null, form, "查询录像列表失败");
        List<RecordingSpan> spans = new ArrayList<>();
        for (JsonNode node : root.path("data")) {
            long begin = node.path("beginTime").asLong(0);
            long end = node.path("endTime").asLong(0);
            if (begin <= 0 || end <= begin) {
                continue;
            }
            spans.add(new RecordingSpan(
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(begin), ZONE),
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(end), ZONE),
                    findText(node, "videoUrl") != null ? findText(node, "videoUrl") : findText(node, "url")));
        }
        return spans;
    }

    /** 获取指定时间段的 HLS 点播地址（云录像在线回看/下载） */
    public String getHlsAddress(EzvizAccount account, String deviceSerial, int channelNo,
                                LocalDateTime from, LocalDateTime to) {
        String token = tokenService.getToken(account, false);
        Map<String, String> headers = Map.of(
                "accessToken", token,
                "deviceSerial", deviceSerial,
                "localIndex", String.valueOf(channelNo));
        Map<String, Object> form = new HashMap<>();
        form.put("startTime", DATE_TIME.format(from));
        form.put("endTime", DATE_TIME.format(to));
        form.put("format", "HLS");
        JsonNode root = tokenService.postForm("/api/service/open/vod/file/address/hls", headers, form,
                "获取 HLS 播放地址失败");
        String address = findText(root, "hlsAddress");
        if (address == null || address.isBlank()) {
            address = findText(root, "url");
        }
        if (address == null || address.isBlank()) {
            throw new EzvizApiException("hls_empty", "萤石未返回 HLS 地址，请确认该时间段存在云录像");
        }
        return address;
    }

    /**
     * 把时间范围按小时切分，避免一次跨度过大（HLS 接口要求 ≤48h）。
     */
    public List<RecordingSpan> splitBySegment(LocalDateTime from, LocalDateTime to, int segmentMinutes) {
        List<RecordingSpan> result = new ArrayList<>();
        int minutes = Math.max(segmentMinutes, 1);
        LocalDateTime cursor = from;
        while (cursor.isBefore(to)) {
            LocalDateTime next = cursor.plusMinutes(minutes);
            if (next.isAfter(to)) {
                next = to;
            }
            result.add(new RecordingSpan(cursor, next));
            cursor = next;
        }
        return result;
    }

    /** 递归查找 JSON 中指定字段的第一个非空字符串值 */
    private String findText(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(field);
            if (direct != null && direct.isTextual() && !direct.asText().isBlank()) {
                return direct.asText();
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String found = findText(entry.getValue(), field);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findText(child, field);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 用于调试：列出返回 JSON 中出现过的顶层字段 */
    public Set<String> topLevelFields(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        log.debug("萤石返回字段: {}", names);
        return names;
    }
}
