package com.pophie;

import com.pophie.service.SpeechService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动日志，对应 main.py 的 @app.on_event("startup")（提醒调度器由 @Scheduled 自动启动）。 */
@Component
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger("pophie");

    private final SpeechService speech;

    public StartupRunner(SpeechService speech) {
        this.speech = speech;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (speech.isEnabled()) {
            log.info("[startup] speech enabled STT={} TTS={}", speech.sttEngine(), speech.ttsEngine());
        } else {
            log.info("[startup] 语音模块未启用 (speech.enabled=false)");
        }
    }
}
