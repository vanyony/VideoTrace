package com.videotrace.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.videotrace.dto.VideoContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Shared corpus evaluation using the actual event handler with a captured JDBC write boundary. */
class LayeredValidationEvaluationTest {
 @Test @SuppressWarnings({"unchecked","rawtypes"}) void replayToolBypassAtPersistenceBoundary() throws Exception {
  var json=new ObjectMapper(); String output=System.getProperty("layerEvalDir");
  var results=json.createArrayNode();
  JsonNode samples;
  try(var input=getClass().getResourceAsStream("/evaluation/layered-validation-cases.json")) {samples=json.readTree(input);}
  for(var sample:samples) {
   var db=mock(JdbcTemplate.class); var manager=mock(PlatformTransactionManager.class);
   when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
   var media=mock(MediaService.class); var checkpoints=mock(SourceCheckpointService.class);
   when(checkpoints.loadContext(2L)).thenReturn(new VideoContext("source","",List.of(new VideoContext.VideoSegment(0,10000,"事实",List.of(),List.of()))));
   var task=json.createObjectNode().put("taskId","task-a").put("ownerId",1).put("mediaId",2).put("sourceVersion","v1")
     .put("status","DRAFTING").put("currentOperationId","op-a").put("durationSeconds",sample.path("parameters").path("durationSeconds").asInt(30));
   var op=json.createObjectNode().put("operationId","op-a").put("taskId","task-a").put("ownerId",1).put("attempt",1).put("sequence",0).put("type",sample.path("type").asText());
   op.set("parameters",sample.path("parameters"));
   when(db.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(inv -> {
    String sql=inv.getArgument(0);return List.of(sql.contains("creation_operations")?op.deepCopy():task.deepCopy());
   });
   var writes=new ArrayList<String>();
   var saved=new ArrayList<JsonNode>();
   when(db.update(anyString(),any(Object[].class))).thenAnswer(inv->{
    String sql=inv.getArgument(0);writes.add(sql);
    if(sql.contains("UPDATE creation_tasks"))saved.add(json.readTree((String)inv.getArgument(1)).path("storyboard"));
    return 1;
   });
   var service=new CreationService(db,manager,json,media,checkpoints,null,null,null,null,"v1","secret","http://127.0.0.1:8091",true);
   var event=json.createObjectNode().put("operationId","op-a").put("attempt",1).put("sequence",1).put("status","AWAITING_CONFIRMATION");
   event.set("storyboard",sample.path("storyboard"));
   var row=results.addObject().put("id",sample.path("id").asText()).put("inputMode",sample.path("inputMode").asText()); long start=System.nanoTime();
   boolean accepted=false;
   try {service.event("task-a",event); accepted=true;row.put("accepted",true);verify(manager).commit(any());assertEquals(2,writes.size());}
   catch(IllegalArgumentException e){row.put("accepted",false).put("error",e.getMessage());assertTrue(writes.isEmpty(),"Invalid callback wrote SQL");verify(manager).rollback(any());}
   row.put("writeCount",writes.size()).put("milliseconds",(System.nanoTime()-start)/1_000_000.0);
   if(!saved.isEmpty())row.set("persistedStoryboard",saved.get(0));
   if(sample.path("invalid").asBoolean())assertFalse(accepted,sample.path("id").asText());
   if(Set.of("valid-baseline","valid-15seconds","valid-local-revision","missing-points-normalized").contains(sample.path("id").asText()))assertTrue(accepted,sample.path("id").asText());
  }
  if(output!=null)json.writerWithDefaultPrettyPrinter().writeValue(Path.of(output).resolve("java-results.json").toFile(),results);
 }
}
