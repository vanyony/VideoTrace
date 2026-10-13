package com.videotrace.service;

import com.videotrace.common.ErrorCode;
import com.videotrace.entity.*;
import com.videotrace.exception.BusinessException;
import com.videotrace.mapper.MediaFileMapper;
import com.videotrace.mapper.UploadSessionMapper;
import com.videotrace.utils.MinioUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ChunkUploadService {
    private static final Logger log = LoggerFactory.getLogger(ChunkUploadService.class);
    private static final long CHUNK_BYTES = 5L * 1024 * 1024;
    private static final int MAX_CHUNKS = 410;
    private final RedissonClient locks;
    private final MinioUtils minio;
    private final MediaService mediaService;
    private final UploadSessionMapper sessions;
    private final MediaFileMapper media;
    private final TransactionTemplate tx;

    public ChunkUploadService(RedissonClient locks, MinioUtils minio, MediaService mediaService,
                              UploadSessionMapper sessions, MediaFileMapper media,
                              PlatformTransactionManager transactions) {
        this.locks = locks;
        this.minio = minio;
        this.mediaService = mediaService;
        this.sessions = sessions;
        this.media = media;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public String initialize(String filename, int chunks, Long userId) throws IOException {
        return initialize(filename, chunks, null, userId);
    }

    public String initialize(String filename, int chunks, Long bytes, Long userId) throws IOException {
        String normalized = mediaService.normalizeVideoFilename(filename);
        if (userId == null) throw new SecurityException("missing authenticated user");
        if (chunks < 1 || chunks > MAX_CHUNKS) throw new IllegalArgumentException("invalid totalChunks");
        if (bytes != null && (bytes < 1 || bytes > CHUNK_BYTES * MAX_CHUNKS
                || (bytes + CHUNK_BYTES - 1) / CHUNK_BYTES != chunks)) {
            throw new IllegalArgumentException("invalid totalBytes or totalChunks");
        }
        String id = UUID.randomUUID().toString();
        if (sessions.insert(id, userId, normalized, chunks, bytes) != 1) {
            throw new IllegalStateException("upload session was not saved");
        }
        return id;
    }

    public Set<Integer> uploadedChunks(String id, Long userId) {
        UploadSession session = owned(id, userId);
        Set<Integer> parts = new TreeSet<>();
        if ("COMPLETED".equals(session.state())) {
            for (int i = 0; i < session.totalChunks(); i++) parts.add(i);
        } else {
            sessions.parts(id).forEach(p -> parts.add(p.partIndex()));
        }
        return parts;
    }

    public void uploadChunk(String id, int index, int chunks, MultipartFile chunk, Long userId) throws Exception {
        validateId(id);
        RLock read = locks.getReadWriteLock("lock:upload:" + id).readLock();
        if (!read.tryLock()) throw conflict("上传任务正在合并或清理，请稍后重试");
        RLock partLock = null;
        try {
            UploadSession session = owned(id, userId);
            if (chunks != session.totalChunks() || index < 0 || index >= chunks) {
                throw new IllegalArgumentException("invalid chunk index or totalChunks");
            }
            if ("COMPLETED".equals(session.state())) return;
            if (chunk == null || chunk.isEmpty() || chunk.getSize() > CHUNK_BYTES) {
                throw new IllegalArgumentException("chunk size must be between 1 byte and 5MB");
            }
            if (session.expectedBytes() != null) {
                long expected = index == chunks - 1
                        ? session.expectedBytes() - CHUNK_BYTES * (chunks - 1) : CHUNK_BYTES;
                if (chunk.getSize() != expected) throw new IllegalArgumentException("unexpected chunk size");
            }
            partLock = locks.getLock("lock:upload:part:" + id + ":" + index);
            if (!partLock.tryLock()) throw conflict("该分片正在上传，请稍后重试");
            String checksum;
            try (InputStream input = chunk.getInputStream()) {
                MessageDigest digest = digest("SHA-256");
                try (DigestInputStream hashing = new DigestInputStream(input, digest)) {
                    hashing.transferTo(OutputStream.nullOutputStream());
                }
                checksum = HexFormat.of().formatHex(digest.digest());
            }
            UploadPart previous = sessions.parts(id).stream().filter(p -> p.partIndex() == index).findFirst().orElse(null);
            if (previous != null) {
                if (previous.sizeBytes() != chunk.getSize() || !previous.checksum().equals(checksum)) {
                    throw conflict("重复分片内容不一致");
                }
                touch(id);
                return;
            }
            touch(id);
            try (InputStream input = chunk.getInputStream()) {
                minio.uploadObject(partName(id, index), input, chunk.getSize(), "application/octet-stream");
            }
            if (sessions.insertPart(id, index, chunk.getSize(), checksum) != 1) {
                throw conflict("上传会话已过期，请重新上传");
            }
        } finally {
            unlock(partLock);
            unlock(read);
        }
    }

    public MediaFile complete(String id, Long userId) throws Exception {
        validateId(id);
        RLock write = locks.getReadWriteLock("lock:upload:" + id).writeLock();
        if (!write.tryLock()) throw conflict("该上传任务正在上传、合并或清理，请稍后重试");
        Path merged = null;
        try {
            UploadSession session = owned(id, userId);
            if ("COMPLETED".equals(session.state())) return mediaService.requireOwnedMedia(session.mediaId(), userId);
            List<UploadPart> parts = sessions.parts(id);
            if (parts.size() != session.totalChunks()) throw conflict("分片尚未全部上传完成");
            touch(id);
            merged = Files.createTempFile("videotrace-merged-", suffix(session.filename()));
            MessageDigest wholeDigest = digest("MD5");
            try (OutputStream output = new BufferedOutputStream(new DigestOutputStream(Files.newOutputStream(merged), wholeDigest))) {
                for (int i = 0; i < parts.size(); i++) {
                    UploadPart part = parts.get(i);
                    if (part.partIndex() != i) throw conflict("分片序号不完整");
                    MessageDigest partDigest = digest("SHA-256");
                    long before = Files.size(merged);
                    DigestOutputStream hashing = new DigestOutputStream(output, partDigest);
                    minio.copyObjectTo(partName(id, i), hashing);
                    hashing.flush();
                    if (Files.size(merged) - before != part.sizeBytes()
                            || !HexFormat.of().formatHex(partDigest.digest()).equals(part.checksum())) {
                        sessions.forgetPart(id, i);
                        throw conflict("分片校验失败，请续传损坏分片");
                    }
                }
            }
            long size = Files.size(merged);
            if (session.expectedBytes() != null && size != session.expectedBytes()) throw conflict("文件大小校验失败");
            String fileUrl;
            String contentType = Files.probeContentType(merged);
            try (InputStream input = Files.newInputStream(merged)) {
                fileUrl = minio.uploadObject(finalName(session), input, size,
                        contentType == null ? "application/octet-stream" : contentType);
            }
            String md5 = HexFormat.of().formatHex(wholeDigest.digest());
            // Object I/O happens before this short transaction. A stable object key
            // makes an uncertain commit safe to retry without deleting committed media.
            MediaFile result = tx.execute(status -> {
                UploadSession locked = sessions.lock(id);
                if (locked == null || !Objects.equals(locked.userId(), userId)) throw new SecurityException("无权访问该上传任务");
                if ("COMPLETED".equals(locked.state())) return mediaService.requireOwnedMedia(locked.mediaId(), userId);
                if (!"OPEN".equals(locked.state())) throw conflict("上传会话已失效");
                MediaFile file = new MediaFile();
                file.setUserId(userId);
                file.setFilename(locked.filename());
                file.setFilePath(fileUrl);
                file.setContentHash(md5);
                file.setSourceUploadId(id);
                file.setStatus("COMPLETED");
                file.setUploadTime(LocalDateTime.now());
                if (media.insert(file) != 1 || sessions.complete(id, file.getId()) != 1) {
                    throw new IllegalStateException("upload completion was not saved");
                }
                return file;
            });
            try {
                mediaService.rememberContentHash(result.getId(), md5);
                mediaService.invalidateUserList(userId);
            } catch (RuntimeException e) {
                log.warn("upload_cache_refresh_failed uploadId={}", id, e);
            }
            return result;
        } finally {
            try { if (merged != null) Files.deleteIfExists(merged); }
            finally { unlock(write); }
        }
    }

    @Scheduled(fixedDelayString = "${upload.cleanup.scan-ms:60000}")
    public void cleanupUploads() {
        for (UploadSession candidate : sessions.cleanupCandidates()) {
            RLock write = null;
            try {
                write = locks.getReadWriteLock("lock:upload:" + candidate.uploadId()).writeLock();
                if (!write.tryLock()) continue;
                UploadSession session = sessions.find(candidate.uploadId());
                if (session == null || ("OPEN".equals(session.state()) && !session.expired())) continue;
                if ("OPEN".equals(session.state())) {
                    sessions.expire(session.uploadId());
                    session = sessions.find(session.uploadId());
                }
                if ("OPEN".equals(session.state())) continue;
                List<String> objects = new ArrayList<>();
                for (int i = 0; i < session.totalChunks(); i++) objects.add(partName(session.uploadId(), i));
                if ("EXPIRED".equals(session.state())) objects.add(finalName(session));
                minio.removeObjects(objects);
                String id = session.uploadId();
                tx.executeWithoutResult(status -> {
                    sessions.clearParts(id);
                    sessions.cleaned(id);
                });
            } catch (RuntimeException e) {
                log.warn("upload_cleanup_failed uploadId={}", candidate.uploadId(), e);
                sessions.retryCleanup(candidate.uploadId());
            } finally { unlock(write); }
        }
    }

    private UploadSession owned(String id, Long userId) {
        validateId(id);
        UploadSession session = sessions.find(id);
        if (session == null) throw new IllegalArgumentException("uploadId does not exist or has expired");
        if (!Objects.equals(session.userId(), userId)) throw new SecurityException("无权访问该上传任务");
        if ("EXPIRED".equals(session.state()) || ("OPEN".equals(session.state()) && session.expired())) {
            throw new IllegalArgumentException("uploadId does not exist or has expired");
        }
        return session;
    }

    private void touch(String id) {
        if (sessions.touch(id) != 1) throw new IllegalArgumentException("uploadId does not exist or has expired");
    }

    private static void validateId(String id) {
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("noncanonical uploadId");
        }
        catch (Exception e) { throw new IllegalArgumentException("invalid uploadId"); }
    }

    private static void unlock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) lock.unlock();
    }

    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }
    private static String partName(String id, int index) { return "chunk-uploads/" + id + "/part-" + index; }
    private static String finalName(UploadSession session) { return "videos/uploads/" + session.uploadId() + suffix(session.filename()); }
    private static String suffix(String filename) { int dot = filename.lastIndexOf('.'); return dot < 0 ? "" : filename.substring(dot); }
    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
