package com.videotrace.service;

import com.videotrace.entity.*;
import com.videotrace.mapper.*;
import com.videotrace.utils.MinioUtils;
import com.videotrace.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.*;
import java.io.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChunkUploadServiceTest {
    private static final String ID = "1bd4f411-f4d2-4e4b-8b6e-07e032ae3bd2";
    private final RedissonClient locks = mock(RedissonClient.class);
    private final MinioUtils minio = mock(MinioUtils.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final UploadSessionMapper sessions = mock(UploadSessionMapper.class);
    private final MediaFileMapper media = mock(MediaFileMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final List<UploadPart> parts = new ArrayList<>();
    private UploadSession session;
    private MediaFile saved;
    private ChunkUploadService service;
    private RLock lock;

    @BeforeEach
    void setup() throws Exception {
        session = new UploadSession(ID, 7L, "sample.mp4", 2, null, "OPEN", null, false);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[]{1,2,3}));
        parts.add(new UploadPart(0,3,hash));
        parts.add(new UploadPart(1,3,hash));
        when(sessions.find(ID)).thenAnswer(c -> session);
        when(sessions.lock(ID)).thenAnswer(c -> session);
        when(sessions.parts(ID)).thenAnswer(c -> List.copyOf(parts));
        when(sessions.touch(ID)).thenReturn(1);
        when(sessions.cleanupCandidates()).thenAnswer(c -> List.of(session));
        when(sessions.complete(eq(ID),anyLong())).thenAnswer(c -> {
            session = new UploadSession(ID,7L,"sample.mp4",2,null,"COMPLETED",c.getArgument(1),false);
            return 1;
        });
        when(sessions.expire(ID)).thenAnswer(c -> {
            session = new UploadSession(ID,7L,"sample.mp4",2,null,"EXPIRED",null,true);
            return 1;
        });
        when(sessions.forgetPart(eq(ID),anyInt())).thenAnswer(c -> {
            parts.removeIf(p -> p.partIndex() == c.getArgument(1,Integer.class));
            return 1;
        });
        when(sessions.insertPart(eq(ID),anyInt(),anyLong(),anyString())).thenAnswer(c -> {
            parts.add(new UploadPart(c.getArgument(1),c.getArgument(2),c.getArgument(3)));
            parts.sort(Comparator.comparingInt(UploadPart::partIndex));
            return 1;
        });
        when(media.insert(any(MediaFile.class))).thenAnswer(c -> {
            saved = c.getArgument(0);
            saved.setId(42L);
            return 1;
        });
        when(mediaService.requireOwnedMedia(42L,7L)).thenAnswer(c -> saved);
        when(mediaService.normalizeVideoFilename(anyString())).thenAnswer(c -> c.getArgument(0));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        lock = mock(RLock.class);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RReadWriteLock rw = mock(RReadWriteLock.class);
        when(rw.readLock()).thenReturn(lock);
        when(rw.writeLock()).thenReturn(lock);
        when(locks.getReadWriteLock(anyString())).thenReturn(rw);
        when(locks.getLock(anyString())).thenReturn(lock);
        doAnswer(c -> { c.getArgument(1,OutputStream.class).write(new byte[]{1,2,3}); return null; })
                .when(minio).copyObjectTo(anyString(),any(OutputStream.class));
        when(minio.uploadObject(anyString(),any(InputStream.class),anyLong(),anyString()))
                .thenAnswer(c -> "http://localhost:9000/media/" + c.getArgument(0));
        service = newService();
    }

    private ChunkUploadService newService() {
        return new ChunkUploadService(locks,minio,mediaService,sessions,media,transactions);
    }

    @Test
    void lostCompletionResponseSurvivesServiceRestartAndCleanedParts() throws Exception {
        MediaFile result = service.complete(ID,7L);
        parts.clear();
        assertEquals(Set.of(0,1),newService().uploadedChunks(ID,7L));
        assertSame(result,newService().complete(ID,7L));
        assertEquals(ID,result.getSourceUploadId());
        assertEquals("COMPLETED",result.getStatus());
        verify(media,times(1)).insert(any(MediaFile.class));
        verify(minio,times(1)).uploadObject(eq("videos/uploads/" + ID + ".mp4"),any(),eq(6L),anyString());
    }

    @Test
    void lateChunksKeepOwnershipAndCannotRecreateObjects() throws Exception {
        service.complete(ID,7L);
        clearInvocations(minio);
        var chunk = new MockMultipartFile("file",new byte[]{1,2,3});
        service.uploadChunk(ID,0,2,chunk,7L);
        verifyNoInteractions(minio);
        assertThrows(SecurityException.class,() -> service.uploadChunk(ID,0,2,chunk,8L));
        assertThrows(SecurityException.class,() -> service.complete(ID,8L));
    }

    @Test
    void cleanupFailureIsRetriedWithoutLosingCompletionOrFinalObject() throws Exception {
        service.complete(ID,7L);
        doThrow(new IllegalStateException("storage unavailable")).doNothing()
                .when(minio).removeObjects(anyList());
        service.cleanupUploads();
        verify(sessions).retryCleanup(ID);
        verify(sessions,never()).cleaned(ID);
        assertSame(saved,service.complete(ID,7L));
        service.cleanupUploads();
        verify(sessions).cleaned(ID);
        verify(minio,times(2)).removeObjects(List.of("chunk-uploads/" + ID + "/part-0","chunk-uploads/" + ID + "/part-1"));
    }

    @Test
    void corruptedStoredPartIsRemovedFromResumeStatusThenCanBeReuploaded() throws Exception {
        doAnswer(c -> { c.getArgument(1,OutputStream.class).write(new byte[]{9,9,9}); return null; })
                .when(minio).copyObjectTo(contains("part-0"),any());
        assertThrows(BusinessException.class,() -> service.complete(ID,7L));
        assertEquals(Set.of(1),service.uploadedChunks(ID,7L));
        verify(media,never()).insert(any(MediaFile.class));
        service.uploadChunk(ID,0,2,new MockMultipartFile("file",new byte[]{1,2,3}),7L);
        assertEquals(Set.of(0,1),service.uploadedChunks(ID,7L));
    }

    @Test
    void duplicatePartContentCannotOverwriteAcknowledgedObject() throws Exception {
        service.uploadChunk(ID,0,2,new MockMultipartFile("file",new byte[]{1,2,3}),7L);
        assertThrows(BusinessException.class,() -> service.uploadChunk(ID,0,2,new MockMultipartFile("file",new byte[]{3,2,1}),7L));
        verify(minio,never()).uploadObject(anyString(),any(),anyLong(),anyString());
    }

    @Test
    void expiredUploadCleansEvenUnacknowledgedObjectsWhileLiveUploadIsSkipped() {
        service.cleanupUploads();
        verifyNoInteractions(minio);
        session = new UploadSession(ID,7L,"sample.mp4",2,null,"OPEN",null,true);
        assertThrows(IllegalArgumentException.class,() -> service.uploadedChunks(ID,7L));
        service.cleanupUploads();
        verify(minio).removeObjects(List.of("chunk-uploads/" + ID + "/part-0","chunk-uploads/" + ID + "/part-1",
                "videos/uploads/" + ID + ".mp4"));
        verify(sessions).cleaned(ID);
    }

    @Test
    void failedDatabaseWriteLeavesStableObjectForSafeRetry() throws Exception {
        when(media.insert(any(MediaFile.class))).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,() -> service.complete(ID,7L));
        verify(transactions).rollback(any());
        verify(sessions,never()).complete(anyString(),anyLong());
        verify(minio,never()).removeObjects(anyList());
        when(media.insert(any(MediaFile.class))).thenAnswer(c -> {
            saved = c.getArgument(0); saved.setId(42L); return 1;
        });
        assertEquals(42L,service.complete(ID,7L).getId());
        verify(minio,times(2)).uploadObject(eq("videos/uploads/" + ID + ".mp4"),any(),eq(6L),anyString());
    }

    @Test
    void expectedSizeRejectsTruncatedLastPartBeforeStorage() {
        session = new UploadSession(ID,7L,"sample.mp4",1,4L,"OPEN",null,false);
        assertThrows(IllegalArgumentException.class,() -> service.uploadChunk(ID,0,1,
                new MockMultipartFile("file",new byte[]{1,2,3}),7L));
        verifyNoInteractions(minio);
        assertThrows(IllegalArgumentException.class,() -> service.initialize("sample.mp4",2,4L,7L));
    }

    @Test
    void alternateUuidSpellingCannotBypassTheSessionLock() {
        assertThrows(IllegalArgumentException.class,() -> service.complete(ID.toUpperCase(Locale.ROOT),7L));
        verifyNoInteractions(locks,sessions,minio,media);
    }

    @Test
    void unavailableMergeLockPreventsReadsAndWrites() {
        when(lock.tryLock()).thenReturn(false);
        assertThrows(BusinessException.class,() -> service.complete(ID,7L));
        verifyNoInteractions(sessions,minio,media);
    }
}
