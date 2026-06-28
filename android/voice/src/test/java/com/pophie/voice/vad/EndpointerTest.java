package com.pophie.voice.vad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Endpointer 纯逻辑单测（JVM 可跑，无需设备）。 */
public class EndpointerTest {

    private static final float SPEECH = 0.9f;
    private static final float SILENCE = 0.1f;

    @Test
    public void startsAfterMinSpeech() {
        // frameMs=20, minSpeech=200 → 需 10 帧语音才 START
        Endpointer ep = new Endpointer(0.5f, 200, 700, 15000, 20);
        Endpointer.Event ev = Endpointer.Event.NONE;
        for (int i = 0; i < 9; i++) {
            ev = ep.feed(SPEECH);
            assertEquals(Endpointer.Event.NONE, ev);
            assertFalse(ep.isSpeaking());
        }
        ev = ep.feed(SPEECH); // 第 10 帧
        assertEquals(Endpointer.Event.START, ev);
        assertTrue(ep.isSpeaking());
    }

    @Test
    public void endsAfterMaxSilence() {
        Endpointer ep = new Endpointer(0.5f, 100, 300, 15000, 20); // minSpeech=100→5帧, maxSilence=300→15帧
        for (int i = 0; i < 5; i++) ep.feed(SPEECH);
        assertTrue(ep.isSpeaking());
        Endpointer.Event ev = Endpointer.Event.NONE;
        for (int i = 0; i < 14; i++) {
            ev = ep.feed(SILENCE);
            assertEquals(Endpointer.Event.NONE, ev);
        }
        ev = ep.feed(SILENCE); // 第 15 帧静音 → END
        assertEquals(Endpointer.Event.END, ev);
        assertFalse(ep.isSpeaking());
    }

    @Test
    public void shortBlipDoesNotStart() {
        Endpointer ep = new Endpointer(0.5f, 200, 700, 15000, 20);
        for (int i = 0; i < 3; i++) ep.feed(SPEECH); // 60ms < 200ms
        ep.feed(SILENCE);
        assertFalse(ep.isSpeaking());
    }

    @Test
    public void maxSegmentForcesEnd() {
        Endpointer ep = new Endpointer(0.5f, 100, 100000, 200, 20); // maxSegment=200ms
        for (int i = 0; i < 5; i++) ep.feed(SPEECH); // START at 100ms, segment=100ms
        Endpointer.Event ev = ep.feed(SPEECH); // 120
        ev = ep.feed(SPEECH); // 140
        ev = ep.feed(SPEECH); // 160
        ev = ep.feed(SPEECH); // 180
        ev = ep.feed(SPEECH); // 200 → END
        assertEquals(Endpointer.Event.END, ev);
    }
}
