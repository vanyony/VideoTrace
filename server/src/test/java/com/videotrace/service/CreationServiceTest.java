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
        ObjectNode shortBoard=board(); shortBoard.path("scenes").forEach(scene -> ((ObjectNode)scene).put("durationMs",4000));
        assertThrows(IllegalArgumentException.class,()->CreationService.validateStoryboard(shortBoard,1,context));
    }
    @Test void acceptsFifteenSecondStoryboardAndRejectsBelowMinimum() throws Exception {
        var shortBoard=board();
        shortBoard.path("scenes").forEach(scene -> ((ObjectNode)scene).put("durationMs",5000));
        assertDoesNotThrow(()->CreationService.validateStoryboard(shortBoard,1,context));
        ((ObjectNode)shortBoard.path("scenes").get(0)).put("durationMs",4999);
        assertThrows(IllegalArgumentException.class,()->CreationService.validateStoryboard(shortBoard,1,context));
    }
    @Test void validatesLaunchAndKineticDesigns() throws Exception {
        var board=board(); var scene=(ObjectNode)board.path("scenes").get(0);
        var motion=scene.putObject("motion").put("style","launch").put("eyebrow","创作")
            .put("accent","violet").put("transition","push");
        motion.putArray("words").add("提取").add("证据");
        assertDoesNotThrow(()->CreationService.validateStoryboard(board,1,context));
        motion.put("style","kinetic");
        assertDoesNotThrow(()->CreationService.validateStoryboard(board,1,context));
        motion.putArray("words").add("字".repeat(13));
        assertThrows(IllegalArgumentException.class,()->CreationService.validateStoryboard(board,1,context));
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
    @Test void diagramCallbackRejectsUnboundedOrUnknownObjects() throws Exception {
        ObjectNode candidate=board();
        ObjectNode scene=(ObjectNode)candidate.path("scenes").get(0);
        ObjectNode visual=scene.putObject("visual").put("layout","merge").put("theme","dark").put("motion","draw");
        var items=visual.putArray("items");
        items.addObject().put("icon","audio").put("label","语音").put("value",50);
        ObjectNode agent=items.addObject().put("icon","agent").put("label","助手").put("value",50);
        assertDoesNotThrow(()->CreationService.validateStoryboard(candidate,1,context));
        agent.put("icon","arbitrary-code");
        assertThrows(IllegalArgumentException.class,()->CreationService.validateStoryboard(candidate,1,context));
        agent.put("icon","agent").put("value",101);
        assertThrows(IllegalArgumentException.class,()->CreationService.validateStoryboard(candidate,1,context));
    }
    @Test void conversationHistoryKeepsLatestMessagesWithinBudget() {
        var messages=json.createArrayNode();
        for(int i=0;i<30;i++) messages.addObject().put("role","user").put("content",String.valueOf(i).repeat(3000));
        var bounded=CreationService.boundedMessages(messages);
        assertTrue(bounded.size()<=20);
        int total=0;
        for(var message:bounded) { assertTrue(message.path("content").asText().length()<=4000); total+=message.path("content").asText().length(); }
        assertTrue(total<=24000);
        assertEquals("29".repeat(2000),bounded.get(bounded.size()-1).path("content").asText());
    }
    @Test void conversationReplyRequiresMatchingActionAndBoundedText() {
        var event=json.createObjectNode().put("action","DISCUSS").put("reply","先讨论方向");
        assertEquals("先讨论方向",CreationService.validateReply(event,"DISCUSSED"));
        assertThrows(IllegalArgumentException.class,()->CreationService.validateReply(event,"AWAITING_CONFIRMATION"));
        event.put("action","CREATE");
        assertDoesNotThrow(()->CreationService.validateReply(event,"AWAITING_CONFIRMATION"));
        event.put("reply","x".repeat(4001));
        assertThrows(IllegalArgumentException.class,()->CreationService.validateReply(event,"AWAITING_CONFIRMATION"));
    }
    CreationService service(String token) {
        return new CreationService(null,org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),json,null,null,null,null,null,"v1",token,"http://127.0.0.1:8091",true);
    }
    @Test void frameTimestampMustBelongToTheBoundEvidence() {
        assertDoesNotThrow(()->CreationService.validateFrameTimestamp(context,"e_0_10000",5000));
        assertThrows(IllegalArgumentException.class,()->CreationService.validateFrameTimestamp(context,"e_0_10000",10000));
        assertThrows(IllegalArgumentException.class,()->CreationService.validateFrameTimestamp(context,"e_10000_20000",15000));
        assertThrows(IllegalArgumentException.class,()->CreationService.validateFrameTimestamp(context,"e_0_10000",-1));
    }
}
