package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPickTest {
    @Test fun mediaFilesAreAccepted() {
        for (p in listOf("/sdcard/Download/a.mp4", "/x/b.MKV", "/x/c.webm", "/x/d.m4a", "/x/e.mp3", "/x/noext", "/data/files/offline/video/f.mp4", "content://media/1"))
            assertTrue(p, PlaybackPick.isMediaPath(p))
    }

    @Test fun sidecarFilesAreRejected() {
        for (p in listOf("", "  ", "/x/a.jpg", "/x/a.WEBP", "/x/a.en.srt", "/x/a.vtt", "/x/a.info.json", "/x/a.mp4.part"))
            assertFalse(p, PlaybackPick.isMediaPath(p))
    }

    @Test fun dotInFolderIsNotAnExtension() {
        assertTrue(PlaybackPick.isMediaPath("/x/my.jpg.folder/video"))
    }

    @Test fun videoModeSkipsAudioEntries() {
        assertFalse(PlaybackPick.typeMatches(entryIsAudio = true, wantVideo = true))
        assertTrue(PlaybackPick.typeMatches(entryIsAudio = false, wantVideo = true))
        assertTrue(PlaybackPick.typeMatches(entryIsAudio = true, wantVideo = false))
        assertTrue(PlaybackPick.typeMatches(entryIsAudio = false, wantVideo = false))
    }

    @Test fun buttonStates() {
        assertEquals(PlaybackPick.SaveState.OFFLINE, PlaybackPick.saveState(true, true))
        assertEquals(PlaybackPick.SaveState.OFFLINE, PlaybackPick.saveState(true, false))
        assertEquals(PlaybackPick.SaveState.DOWNLOADED, PlaybackPick.saveState(false, true))
        assertEquals(PlaybackPick.SaveState.SAVE, PlaybackPick.saveState(false, false))
    }
}
