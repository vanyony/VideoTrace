package com.videotrace.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.videotrace.common.ErrorCode;
import com.videotrace.exception.BusinessException;
import com.videotrace.service.ExternalCallLimiter;

@Component
public class YtDlpUtils {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUtils.class);

    /** yt-dlp 的进度行，形如 {@code [download]  58.1% of   11.17MiB at  164KiB/s ETA 00:29}。 */
    private static final Pattern DOWNLOAD_PROGRESS = Pattern.compile("^\\[download]\\s+[\\d.]+% of ");

    private final String ytDlpPath;
    private final String ffmpegDir;
    private final String cookiesFile;
    private final String cookiesFromBrowser;
    private final String proxy;
    private final ExternalCallLimiter limiter;

    public YtDlpUtils(@Value("${tool.ytdlp.path}") String ytDlpPath,
                      @Value("${tool.ffmpeg.dir}") String ffmpegDir,
                      @Value("${tool.ytdlp.cookies:}") String cookiesFile,
                      @Value("${tool.ytdlp.cookies-from-browser:}") String cookiesFromBrowser,
                      @Value("${tool.ytdlp.proxy:}") String proxy, ExternalCallLimiter limiter) {
        this.ytDlpPath = ytDlpPath;
        this.ffmpegDir = ffmpegDir;
        this.cookiesFile = cookiesFile;
        this.cookiesFromBrowser = cookiesFromBrowser;
        this.proxy = proxy == null ? "" : proxy.trim();
        if (!this.proxy.isEmpty()) {
            URI uri = URI.create(this.proxy);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty()))
                throw new IllegalArgumentException("下载出口必须配置为 http://host:port 代理");
        }
        this.limiter = limiter;
    }

    public File downloadVideo(String url) throws Exception {
        if (proxy.isEmpty()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "视频链接导入未启用，请配置受控下载代理或使用本地上传");
        validatePublicHttpUrl(url);
        try (var permit = limiter.acquire("media")) {
            return download(url);
        }
    }

    private File download(String url) throws Exception {
        Path outputPath = Path.of(System.getProperty("java.io.tmpdir"), UUID.randomUUID() + ".mp4");
        Path logPath = Files.createTempFile("yt-dlp-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(downloadCommand(url, outputPath))
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                throw new IllegalStateException("视频链接下载超时");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(outputPath)) {
                String logs = Files.readString(logPath);
                throw new IllegalStateException("yt-dlp 下载失败: " + recentLogs(logs));
            }
            log.info("url_video_downloaded host={} bytes={}", URI.create(url).getHost(), Files.size(outputPath));
            return outputPath.toFile();
        } catch (Exception e) {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            Files.deleteIfExists(outputPath);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(logPath);
        }
    }

    List<String> downloadCommand(String url, Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--ignore-config");
        command.add("--no-plugin-dirs");
        command.add("--proxy");
        command.add(proxy);
        command.add("--hls-prefer-native");
        command.add("--no-playlist");
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("10");
        // 分片重试单独设：默认继承 --retries，但分段下载（HLS/DASH）的失败模式是
        // 单分片反复断连，与整文件重试不是一回事，显式给足次数更稳。
        command.add("--fragment-retries");
        command.add("10");
        command.add("--max-filesize");
        command.add("2048M");
        // 优先选广泛兼容的 H.264/AVC + AAC 组合：仅把 AV1 文件换个 MP4 容器，
        // 在部分 macOS 与硬件组合的 Safari 上仍然放不出来。
        //
        // 分辨率与帧率分层降级：分析链路只用关键帧和音轨，4K 60帧对结果没有任何增益，
        // 却让体积涨近十倍，进而被站点限速掐断连接（实测 B 站 4K 源 386MB，传 25MB 即断）。
        // 因此依次尝试「≤1080p 且 ≤30fps → ≤1080p → 原行为」，保证源没有低规格档时仍可下载。
        command.add("-f");
        command.add("bv*[vcodec^=avc1][ext=mp4][height<=1080][fps<=30]+ba[acodec^=mp4a][ext=m4a]"
                + "/bv*[vcodec^=avc1][ext=mp4][height<=1080]+ba[acodec^=mp4a][ext=m4a]"
                + "/b[vcodec^=avc1][ext=mp4][height<=1080]"
                + "/bv*[vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]"
                + "/b[vcodec^=avc1][ext=mp4]");
        command.add("--merge-output-format");
        command.add("mp4");
        command.add("--recode-video");
        command.add("mp4");
        if (ffmpegDir != null && !ffmpegDir.isBlank()) {
            command.add("--ffmpeg-location");
            command.add(ffmpegDir);
        }
        appendCookieArgs(command);
        command.add("-o");
        command.add(outputPath.toString());
        command.add("--");
        command.add(url);

        return command;
    }

    /**
     * 部分站点（实测 B 站）对缺少登录态的请求直接返回 HTTP 412 风控页，
     * 仅靠 User-Agent 或手工构造的 buvid3 都绕不过，必须带真实浏览器会话 cookie。
     * 两种来源互斥，显式 cookie 文件优先：服务端用它更可控，也不依赖运行时能否访问浏览器 cookie 存储
     * （macOS 上读取浏览器 cookie 需要钥匙串授权，无人值守时会失败）。
     */
    private void appendCookieArgs(List<String> command) {
        if (cookiesFile != null && !cookiesFile.isBlank()) {
            command.add("--cookies");
            command.add(cookiesFile);
            return;
        }
        if (cookiesFromBrowser != null && !cookiesFromBrowser.isBlank()) {
            command.add("--cookies-from-browser");
            command.add(cookiesFromBrowser);
        }
    }

    private void validatePublicHttpUrl(String value) throws Exception {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅支持合法的公网 HTTP/HTTPS 视频链接");
        }
        InetAddress[] resolved = InetAddress.getAllByName(host);
        if (resolved.length == 0) {
            throw new IllegalArgumentException("无法解析视频链接的主机地址");
        }
        for (InetAddress address : resolved) {
            if (isDisallowedAddress(address)) {
                throw new IllegalArgumentException("不允许访问本机、内网或保留网段地址");
            }
        }
        // 注意：这里只是应用层的尽力校验（defense-in-depth）。yt-dlp 子进程会对 host
        // 重新做一次 DNS 解析并可能跟随 302 重定向，存在 DNS rebinding / 跳转到内网的
        // TOCTOU 风险，单靠应用层字符串/首次解析校验无法彻底封堵。生产环境必须叠加网络层
        // 出口管控。下载必须经过配置的受控 HTTP 代理，代理应逐次解析并拒绝内网目的地；
        // 部署还应阻断下载进程绕过代理直接出网（包括媒体工具的子进程）。
    }

    /**
     * 拦截不应从服务端访问的地址：回环、任意本地、链路本地（含云元数据端点
     * 169.254.169.254）、站点内网（RFC1918）、IPv6 ULA、运营商级 NAT、组播与保留网段。
     */
    private boolean isDisallowedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()          // 0.0.0.0 / ::
                || address.isLoopbackAddress()   // 127.0.0.0/8 / ::1
                || address.isLinkLocalAddress()  // 169.254.0.0/16（含云元数据端点）/ fe80::/10
                || address.isSiteLocalAddress()  // 10/8、172.16/12、192.168/16
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0) return true;                                     // 0.0.0.0/8 “本网络”
            if (first == 100 && second >= 64 && second <= 127) return true;  // 100.64.0.0/10 运营商级 NAT
            if (first == 169 && second == 254) return true;                  // 169.254.0.0/16 兜底
            return first >= 240;                                             // 240.0.0.0/4 保留段
        }
        if (bytes.length == 16) {
            return (bytes[0] & 0xFE) == 0xFC;                                // fc00::/7 IPv6 唯一本地地址(ULA)
        }
        return false;
    }

    /**
     * yt-dlp 的进度行用 \r 原地刷新，一个百兆视频能刷出上百行；直接截尾取错误信息会把真正的报错
     * 整个挤出窗口（实测踩过：只剩满屏 [download] 百分比，看不到失败原因）。这里先剔除进度行再截断。
     * 剔除后若什么都不剩，则退回原始日志，避免把全部上下文也一起丢掉。
     */
    private String recentLogs(String logs) {
        String cleaned = logs.lines()
                .filter(line -> !DOWNLOAD_PROGRESS.matcher(line.trim()).find())
                .collect(Collectors.joining("\n"));
        return tail(cleaned.isBlank() ? logs : cleaned, 2_000);
    }

    private String tail(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(value.length() - maxLength);
    }
}
