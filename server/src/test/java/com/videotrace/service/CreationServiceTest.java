package com.videotrace.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videotrace.dto.VideoContext;
import com.videotrace.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CreationServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final VideoContext context=new VideoContext("source","",List.of(new VideoContext.VideoSegment(0,10000,"素材事实",List.of(),List.of())));
    ObjectNode board() throws Exception {
        return (ObjectNode)json.readTree("""
            {"schemaVersion":1,"revision":1,"title":"标题","targetAudience":"公众","musicId":"none","scenes":[
            {"sceneId":"s1","template":"title","animation":"fade","durationMs":10000,"headline":"第一点","points":[],"sourceRefs":["e_0_10000"]},
            {"sceneId":"s2","template":"points","animation":"reveal","durationMs":10000,"headline":"第二点","points":["证据"],"sourceRefs":["e_0_10000"]},
            {"sceneId":"s3","template":"flow","animation":"highlight","durationMs":10000,"headline":"第三点","points":[],"sourceRefs":["e_0_10000"]}]}
            """);
    }
    @Test void readinessRequiresTimestampedTextRatherThanOnlyAContextShell() {
        assertFalse(CreationService.ready(null));
        assertFalse(CreationService.ready(new VideoContext("source","",List.of())));
        assertFalse(CreationService.ready(new VideoContext("source","",List.of(new VideoContext.VideoSegment(0,1,"",List.of(),List.of())))));
        assertTrue(CreationService.ready(context));
    }
    @Test void validatesVersionEvidenceAndTimelineAtCallbackBoundary() throws Exception {
        ObjectNode board=board();
        assertDoesNotThrow(()->CreationService.validateStoryboard(board,1,context));
        assertThrows(IllegalArgumentException.class,()->CreationService.validateStoryboard(board,2,context));
        ((ObjectNode)board.path("scenes").get(0)).putArray("sourceRefs").add("e_10000_20000");
        assertThrows(IllegalArgumentException.class,()->CreationService.validateStoryboard(board,1,context));
        ObjectNode shortBoard=board(); ((ObjectNode)shortBoard.path("scenes").get(0)).put("durationMs",2000);
        assertThrows(IllegalArgumentException.class,()->CreationService.validateStoryboard(shortBoard,1,context));
    }
    @Test void localRevisionMustKeepNonTargetScenesAndGlobalFields() throws Exception {
        ObjectNode previous=board(),next=board();
        ObjectNode params=json.createObjectNode(); params.set("storyboard",previous); params.putArray("targetSceneIds").add("s2");
        ((ObjectNode)next.path("scenes").get(1)).put("headline","新观点");
        assertDoesNotThrow(()->CreationService.validateTargets(params,next));
        ((ObjectNode)next.path("scenes").get(0)).put("headline","不允许更改");
        assertThrows(IllegalArgumentException.class,()->CreationService.validateTargets(params,next));
        final var changed=board(); changed.put("title","其他标题");
        assertThrows(IllegalArgumentException.class,()->CreationService.validateTargets(params,changed));
    }
    @Test void serviceAuthenticationFailsClosedAndRequiresExactToken() {
        var empty=service("");
        assertThrows(BusinessException.class,()->empty.authenticate(""));
        var configured=service("secret");
        assertThrows(BusinessException.class,()->configured.authenticate(null));
        assertThrows(BusinessException.class,()->configured.authenticate("wrong"));
        assertDoesNotThrow(()->configured.authenticate("secret"));
    }
    CreationService service(String token) {
        return new CreationService(null,org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),json,null,null,null,null,"v1",token,"http://127.0.0.1:8091",true);
    }
}
