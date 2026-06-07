package com.video.realtime.controller;

import com.video.realtime.dto.RealtimeChunkResponse;
import com.video.realtime.dto.RealtimeSessionResponse;
import com.video.realtime.service.RealtimeInterpretationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/realtime")
@CrossOrigin(origins = "*")
public class RealtimeController {

    @Autowired
    private RealtimeInterpretationService realtimeInterpretationService;

    @PostMapping("/session/start")
    public ResponseEntity<RealtimeSessionResponse> startSession() {
        return ResponseEntity.ok(realtimeInterpretationService.startSession());
    }

    @PostMapping(value = "/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RealtimeChunkResponse> processChunk(
        @RequestParam("sessionId") String sessionId,
        @RequestParam("chunkStartMs") long chunkStartMs,
        @RequestParam("chunkEndMs") long chunkEndMs,
        @RequestParam("audioChunk") MultipartFile audioChunk,
        @RequestParam(value = "language", required = false, defaultValue = "auto") String language
    ) {
        return ResponseEntity.ok(realtimeInterpretationService.processChunk(
            sessionId,
            chunkStartMs,
            chunkEndMs,
            audioChunk,
            language
        ));
    }

    @GetMapping("/session/{sessionId}/subtitles")
    public ResponseEntity<RealtimeChunkResponse> getSubtitles(@PathVariable String sessionId) {
        return ResponseEntity.ok(realtimeInterpretationService.getSubtitles(sessionId));
    }

    @PostMapping("/session/{sessionId}/finish")
    public ResponseEntity<RealtimeChunkResponse> finishSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(realtimeInterpretationService.finishSession(sessionId));
    }
}
