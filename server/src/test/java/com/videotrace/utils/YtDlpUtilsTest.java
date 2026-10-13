package com.videotrace.utils;

import com.videotrace.exception.BusinessException;
import com.videotrace.service.ExternalCallLimiter;
import com.videotrace.exception.ResourceBusyException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class YtDlpUtilsTest {
    @Test void noProxyDisablesDownloadsBeforeAnyProcessOrPermit() {
        ExternalCallLimiter limiter = mock(ExternalCallLimiter.class);
        YtDlpUtils downloader = new YtDlpUtils("missing-executable", "", "", "", "", limiter);
        assertThrows(BusinessException.class, () -> downloader.downloadVideo("https://example.com/video"));
        verifyNoInteractions(limiter);
    }

    @Test void invalidProxyConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new YtDlpUtils("yt-dlp", "", "", "",
                "socks5://localhost:1080", mock(ExternalCallLimiter.class)));
    }

    @Test void internalInitialDestinationIsRejectedEvenWithProxy() {
        ExternalCallLimiter limiter = mock(ExternalCallLimiter.class);
        YtDlpUtils downloader = new YtDlpUtils("missing-executable", "", "", "",
                "http://127.0.0.1:3128", limiter);
        assertThrows(IllegalArgumentException.class, () -> downloader.downloadVideo("http://127.0.0.1/video"));
        verifyNoInteractions(limiter);
    }

    @Test void saturatedMediaPoolRejectsDownloadBeforeStartingProcess() {
        ExternalCallLimiter limiter = mock(ExternalCallLimiter.class);
        when(limiter.acquire("media")).thenThrow(new ResourceBusyException());
        YtDlpUtils downloader = new YtDlpUtils("missing-executable", "", "", "",
                "http://127.0.0.1:3128", limiter);
        assertThrows(ResourceBusyException.class, () -> downloader.downloadVideo("https://8.8.8.8/video"));
    }

    @Test void failedProcessStartReleasesItsMediaPermit() {
        ExternalCallLimiter limiter = mock(ExternalCallLimiter.class);
        ExternalCallLimiter.Permit permit = mock(ExternalCallLimiter.Permit.class);
        when(limiter.acquire("media")).thenReturn(permit);
        YtDlpUtils downloader = new YtDlpUtils("videotrace-test-missing-executable", "", "", "",
                "http://127.0.0.1:3128", limiter);
        assertThrows(java.io.IOException.class, () -> downloader.downloadVideo("https://8.8.8.8/video"));
        verify(permit).close();
    }

    @Test void downloadCommandForcesProxyAndCannotReadLocalOverrides() {
        YtDlpUtils downloader = new YtDlpUtils("yt-dlp", "", "", "",
                "http://127.0.0.1:3128", mock(ExternalCallLimiter.class));
        var command = downloader.downloadCommand("https://8.8.8.8/video", Path.of("output.mp4"));
        assertEquals("http://127.0.0.1:3128", command.get(command.indexOf("--proxy") + 1));
        assertTrue(command.contains("--ignore-config"));
        assertTrue(command.contains("--no-plugin-dirs"));
        assertEquals("--", command.get(command.size() - 2));
    }
}
