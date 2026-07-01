package com.pophie.service;

import com.pophie.entity.VoiceSegmentLogEntity;
import com.pophie.exception.ApiException;
import com.pophie.repository.VoiceSegmentLogRepository;
import com.pophie.schema.AudioPayload;
import com.pophie.schema.SpeakerSnapshot;
import com.pophie.schema.SttResult;
import com.pophie.schema.VoiceSegmentSttPatchRequest;
import com.pophie.schema.VoiceSegmentUploadRequest;
import com.pophie.schema.VoiceSegmentUploadResponse;
import com.pophie.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VoiceSegmentLogService {

    private static final Logger log = LoggerFactory.getLogger("pophie.voice");

    private final VoiceSegmentLogRepository repo;
    private final ChatService chatService;
    private final RobotService robotService;
    private final SpeechService speech;

    public VoiceSegmentLogService(VoiceSegmentLogRepository repo, ChatService chatService,
                                  RobotService robotService, SpeechService speech) {
        this.repo = repo;
        this.chatService = chatService;
        this.robotService = robotService;
        this.speech = speech;
    }

    public VoiceSegmentUploadResponse ingest(VoiceSegmentUploadRequest req) {
        if (req == null) throw new ApiException(400, "请求体不能为空");
        String robotId = chatService.resolveRobot(req.getRobotId());
        String userId = chatService.resolveUser(req.getUserId());
        String sessionId = chatService.ensureSession(req.getSessionId());
        robotService.touchRobot(robotId);

        VoiceSegmentLogEntity row = new VoiceSegmentLogEntity();
        row.setRobotId(robotId);
        row.setUserId(userId);
        row.setSessionId(sessionId);
        row.setClientSegmentId(trimToNull(req.getClientSegmentId()));
        row.setDurationMs(req.getDurationMs());
        row.setSampleRate(req.getSampleRate());

        SpeakerSnapshot spk = req.getSpeaker();
        if (spk != null) {
            row.setSpeakerName(trimToNull(spk.getName()));
            row.setSpeakerState(trimToNull(spk.getState()));
            row.setOwner(spk.isOwner());
            row.setConfidence(spk.getConfidence());
            row.setMargin(spk.getMargin());
            row.setRunnerUp(trimToNull(spk.getRunnerUp()));
            row.setRawScore(spk.getRawScore());
        }

        String sttText = trimToNull(req.getSttText());
        String sttSource = trimToNull(req.getSttSource());
        if (sttSource == null) sttSource = sttText != null ? "client" : "none";

        AudioPayload audio = req.getAudio();
        if (audio != null && audio.getData() != null && !audio.getData().isBlank()) {
            row.setAudioFormat(audio.getFormat() != null ? audio.getFormat() : "wav");
            row.setAudioEncoding(audio.getEncoding() != null ? audio.getEncoding() : "base64");
            row.setAudioData(audio.getData());
        }

        if (sttText == null && req.isServerSttIfEmpty() && audio != null
                && audio.getData() != null && !audio.getData().isBlank() && speech.isEnabled()) {
            try {
                SttResult stt = speech.transcribe(audio);
                if (stt != null && stt.getText() != null && !stt.getText().isBlank()) {
                    sttText = stt.getText().trim();
                    sttSource = "server";
                }
            } catch (Exception e) {
                log.warn("[voice.segment] server STT failed: {}", e.getMessage());
            }
        }

        row.setSttText(sttText);
        row.setSttSource(sttSource != null ? sttSource : "none");

        if (req.getEmbedding() != null && !req.getEmbedding().isEmpty()) {
            row.setEmbedding(JsonUtil.dumps(req.getEmbedding()));
        }
        if (req.getMetadata() != null && !req.getMetadata().isEmpty()) {
            row.setMetadata(JsonUtil.dumps(req.getMetadata()));
        }

        row = repo.save(row);
        log.info("[voice.segment] saved id={} dur={}ms owner={} stt={} src={} audio={}",
                row.getId(), row.getDurationMs(), row.isOwner(),
                sttText != null ? sttText.substring(0, Math.min(40, sttText.length())) : "",
                row.getSttSource(),
                row.getAudioData() != null && !row.getAudioData().isBlank());
        return new VoiceSegmentUploadResponse(
                row.getId(), sessionId, row.getSttText(), row.getSttSource(), row.getCreatedAt());
    }

    /** 实时 STT final 晚于流水入库时，补写识别文本。 */
    public VoiceSegmentUploadResponse patchStt(long id, VoiceSegmentSttPatchRequest req) {
        VoiceSegmentLogEntity row = repo.findById(id)
                .orElseThrow(() -> new ApiException(404, "voice segment not found"));
        String text = trimToNull(req.getSttText());
        if (text == null) throw new ApiException(400, "stt_text 不能为空");
        row.setSttText(text);
        String src = trimToNull(req.getSttSource());
        row.setSttSource(src != null ? src : "realtime");
        row = repo.save(row);
        log.info("[voice.segment] patched id={} stt={}", id, text.substring(0, Math.min(40, text.length())));
        return new VoiceSegmentUploadResponse(
                row.getId(), row.getSessionId(), row.getSttText(), row.getSttSource(), row.getCreatedAt());
    }

    public Map<String, Object> list(String robotId, String userId, String sessionId,
                                    Boolean owner, int limit) {
        String rid = chatService.resolveRobot(robotId);
        int cap = Math.min(Math.max(limit, 1), 200);
        String uid = trimToNull(userId);
        String sid = trimToNull(sessionId);
        List<VoiceSegmentLogEntity> rows = repo.search(rid, uid, sid, owner, PageRequest.of(0, cap));
        List<Object> items = new ArrayList<>();
        for (VoiceSegmentLogEntity r : rows) {
            items.add(toSummary(r, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    public Map<String, Object> getById(long id, boolean includeAudio) {
        VoiceSegmentLogEntity r = repo.findById(id)
                .orElseThrow(() -> new ApiException(404, "voice segment not found"));
        return toSummary(r, includeAudio);
    }

    private static Map<String, Object> toSummary(VoiceSegmentLogEntity r, boolean includeAudio) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", r.getId());
        d.put("robot_id", r.getRobotId());
        d.put("user_id", r.getUserId());
        d.put("session_id", r.getSessionId());
        d.put("client_segment_id", r.getClientSegmentId());
        d.put("duration_ms", r.getDurationMs());
        d.put("sample_rate", r.getSampleRate());
        d.put("speaker_name", r.getSpeakerName());
        d.put("speaker_state", r.getSpeakerState());
        d.put("is_owner", r.isOwner());
        d.put("confidence", r.getConfidence());
        d.put("margin", r.getMargin());
        d.put("runner_up", r.getRunnerUp());
        d.put("raw_score", r.getRawScore());
        d.put("stt_text", r.getSttText());
        d.put("stt_source", r.getSttSource());
        d.put("has_audio", r.getAudioData() != null && !r.getAudioData().isBlank());
        if (includeAudio && r.getAudioData() != null) {
            Map<String, Object> audio = new LinkedHashMap<>();
            audio.put("format", r.getAudioFormat());
            audio.put("encoding", r.getAudioEncoding());
            audio.put("sample_rate", r.getSampleRate());
            audio.put("data", r.getAudioData());
            d.put("audio", audio);
        }
        if (r.getEmbedding() != null && !r.getEmbedding().isBlank()) {
            d.put("embedding", JsonUtil.parseList(r.getEmbedding()));
        }
        d.put("metadata", JsonUtil.parseMap(r.getMetadata()));
        d.put("created_at", r.getCreatedAt());
        return d;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
