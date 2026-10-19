package com.videotrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videotrace.common.ErrorCode;
import com.videotrace.dto.VideoContext;
import com.videotrace.exception.BusinessException;
import com.videotrace.utils.MinioUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** MySQL is the durable queue and authority for public creation state. */
@Service
public class CreationService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final MediaService media;
    private final AgentCheckpointService checkpoints;
    private final LongVideoContextService retrieval;
    private final MinioUtils minio;
    private final VideoFrameService frames;
    private final String version, token, python;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CreationService.class);

    public CreationService(JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager manager,
            ObjectMapper json, MediaService media, AgentCheckpointService checkpoints,
            LongVideoContextService retrieval, MinioUtils minio, VideoFrameService frames,
            @Value("${video.parse.version:v1}") String version,
            @Value("${creation.internal-token:}") String token,
            @Value("${creation.url:http://127.0.0.1:8091}") String python,
            @Value("${creation.enabled:true}") boolean enabled) {
        this.db=db; this.tx=new TransactionTemplate(manager); this.json=json; this.media=media;
        this.checkpoints=checkpoints; this.retrieval=retrieval; this.minio=minio; this.frames=frames;
        this.version=version; this.token=token; this.python=python.replaceAll("/+$", ""); this.enabled=enabled;
    }

    public void authenticate(String supplied) {
        if (token.isBlank() || supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw error(ErrorCode.UNAUTHORIZED,"无效的创作服务令牌");
    }

    public ObjectNode eligibility(long owner, long mediaId) {
        media.requireOwnedMedia(mediaId,owner);
        boolean ready = ready(checkpoints.loadContext(mediaId));
        return json.createObjectNode().put("eligible",enabled && ready).put("reason",!enabled ? "创作服务未启用" : ready ? "" : "请先完成当前版本的视频分析，素材需要带时间戳的解析上下文").put("sourceVersion",version);
    }

    static boolean ready(VideoContext context) {
        return context != null && context.segments().stream().anyMatch(s -> s.endMs()>s.startMs()
                && (!s.transcript().isBlank() || s.ocrTexts().stream().anyMatch(t -> t!=null && !t.isBlank())));
    }

    public JsonNode create(long owner, JsonNode request) { return create(owner,request,"CREATE"); }

    public JsonNode createConversation(long owner, JsonNode request) { return create(owner,request,"CONVERSE"); }

    public JsonNode message(long owner,String taskId,JsonNode request) { return change(owner,taskId,"CONVERSE",request); }

    private JsonNode create(long owner, JsonNode request,String type) {
        requireEnabled();
        long mediaId=positive(request,"mediaId");
        text(request,"requirement",2000); requestId(request);
        int duration=request.path("durationSeconds").asInt(45);
        if (duration<30 || duration>60) throw error(ErrorCode.INVALID_ARGUMENT,"时长必须为 30～60 秒");
        ObjectNode payload=request.deepCopy(); payload.put("durationSeconds",duration);
        payload.put("type",type);
        if(type.equals("CONVERSE")) payload.put("instruction",text(request,"requirement",2000));
        return tx.execute(s -> {
            // ponytail: serialize submissions per owner; reserve request rows separately if submission throughput grows.
            db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",owner);
            JsonNode previous=duplicate(owner,payload);
            if(previous!=null) return publicTask(previous);
            if(!eligibility(owner,mediaId).path("eligible").asBoolean()) throw error(ErrorCode.CONFLICT,"素材尚未就绪");
            String taskId=UUID.randomUUID().toString();
            ObjectNode task=json.createObjectNode().put("taskId",taskId).put("ownerId",owner).put("mediaId",mediaId)
                    .put("sourceVersion",version).put("requirement",request.path("requirement").asText().trim())
                    .put("durationSeconds",duration).put("status","QUEUED").put("revision",0)
                    .put("summary","等待创作服务处理").put("error","").put("retryable",false);
            task.putNull("storyboard");
            db.update("INSERT INTO creation_tasks(task_id,owner_id,media_id,snapshot) VALUES(?,?,?,?)",taskId,owner,mediaId,write(task));
            operation(task,type,payload,1);
            return publicTask(task);
        });
    }

    public JsonNode change(long owner,String taskId,String type,JsonNode request) {
        requireEnabled(); requestId(request);
        ObjectNode payload=request.deepCopy(); payload.put("type",type); payload.put("taskId",taskId);
        if(Set.of("REVISE","CONVERSE").contains(type)) text(request,"instruction",2000);
        return tx.execute(s -> {
            db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",owner);
            ObjectNode task=owned(taskId,owner,true);
            JsonNode previous=duplicate(owner,payload);
            if(previous!=null) return publicTask(previous);
            source(task);
            if(!Set.of("FAILED","AWAITING_CONFIRMATION","COMPLETED","DISCUSSED").contains(task.path("status").asText())) throw error(ErrorCode.CONFLICT,"已有创作操作正在执行");
            String actualType=type;
            int attempt=1;
            if(type.equals("RETRY")) {
                if(!task.path("status").asText().equals("FAILED") || !task.path("retryable").asBoolean()) throw error(ErrorCode.CONFLICT,"此任务不可重试");
                ObjectNode old=op(task.path("currentOperationId").asText(),false);
                actualType=old.path("type").asText();
                payload.set("retryParameters",old.path("parameters"));
                attempt=old.path("attempt").asInt()+1;
            } else {
                if(!request.path("baseRevision").isIntegralNumber() || request.path("baseRevision").asInt(-1)!=task.path("revision").asInt()) throw error(ErrorCode.CONFLICT,"分镜版本已变化，请刷新");
                if(!type.equals("CONVERSE") && (task.path("storyboard").isNull() || task.path("storyboard").isMissingNode())) throw error(ErrorCode.CONFLICT,"尚无可确认的分镜");
                if(request.has("targetSceneIds")) {
                    JsonNode targets=request.path("targetSceneIds"); Set<String> ids=new HashSet<>();
                    task.path("storyboard").path("scenes").forEach(scene -> ids.add(scene.path("sceneId").asText()));
                    if(!targets.isArray() || targets.size()>8) throw new IllegalArgumentException("targetSceneIds 无效");
                    for(JsonNode target:targets) if(!target.isTextual() || !ids.contains(target.asText())) throw new IllegalArgumentException("目标场景不存在");
                }
            }
            operation(task,actualType,payload,attempt);
            return publicTask(task);
        });
    }

    private void operation(ObjectNode task,String type,ObjectNode payload,int attempt) {
        if(task.path("status").asText().equals("COMPLETED") && !task.has("artifactRevision"))
            task.put("artifactRevision",task.path("revision").asInt());
        String id=UUID.randomUUID().toString();
        ObjectNode params=json.createObjectNode().put("requirement",task.path("requirement").asText())
                .put("durationSeconds",task.path("durationSeconds").asInt()).put("baseRevision",task.path("revision").asInt())
                .put("confirmedRevision",task.path("revision").asInt());
        params.set("storyboard",task.path("storyboard"));
        if(payload.has("instruction")) params.set("instruction",payload.get("instruction"));
        if(payload.has("targetSceneIds")) params.set("targetSceneIds",payload.get("targetSceneIds"));
        if(type.equals("CONVERSE") && !payload.has("retryParameters")) {
            if(task.path("messages").size()>=199) throw error(ErrorCode.CONFLICT,"此对话已达到 200 条消息上限，请新建对话");
            appendMessage(task,id+":user","user",payload.path("instruction").asText());
        }
        if(type.equals("CONVERSE")) params.set("messages",boundedMessages(task.path("messages")));
        if(payload.has("retryParameters")) params=(ObjectNode)payload.get("retryParameters");
        ObjectNode op=json.createObjectNode().put("taskId",task.path("taskId").asText()).put("operationId",id)
                .put("ownerId",task.path("ownerId").asLong()).put("mediaId",task.path("mediaId").asLong())
                .put("sourceVersion",task.path("sourceVersion").asText()).put("type",type).put("attempt",attempt).put("sequence",0);
        op.set("parameters",params);
        ObjectNode identity=payload.deepCopy(); identity.remove("retryParameters");
        try {
            db.update("INSERT INTO creation_operations(operation_id,task_id,owner_id,request_id,request_payload,snapshot) VALUES(?,?,?,?,?,?)",id,task.path("taskId").asText(),task.path("ownerId").asLong(),payload.path("requestId").asText(),write(identity),write(op));
        } catch(org.springframework.dao.DuplicateKeyException e) { throw error(ErrorCode.CONFLICT,"requestId 已使用，请重试原请求"); }
        task.put("currentOperationId",id).put("status","QUEUED").put("summary","等待处理").put("error","").put("retryable",false);
        save(task);
    }

    private JsonNode duplicate(long owner,JsonNode payload) {
        var rows=db.queryForList("SELECT request_payload,task_id FROM creation_operations WHERE owner_id=? AND request_id=?",owner,payload.path("requestId").asText());
        if(rows.isEmpty()) return null;
        if(!read((String)rows.get(0).get("request_payload")).equals(payload)) throw error(ErrorCode.CONFLICT,"requestId 已用于不同请求");
        return owned((String)rows.get(0).get("task_id"),owner,false);
    }

    public JsonNode get(long owner,String taskId) { return publicTask(owned(taskId,owner,false)); }
    public List<JsonNode> list(long owner,long mediaId) {
        return db.query("SELECT snapshot FROM creation_tasks WHERE owner_id=? AND media_id=? ORDER BY created_at DESC LIMIT 100",(r,n)->publicTask(read(r.getString(1))),owner,mediaId);
    }
    public JsonNode artifact(long owner,String taskId,Integer revision) {
        ObjectNode task=owned(taskId,owner,false);
        int rev=revision==null?task.path("revision").asInt():revision;
        var rows=db.query("SELECT snapshot FROM creation_artifacts WHERE task_id=? AND revision=?",(r,n)->read(r.getString(1)),taskId,rev);
        if(rows.isEmpty()) throw error(ErrorCode.NOT_FOUND,"此版本尚无成片");
        ObjectNode result=rows.get(0).deepCopy();
        result.put("url",minio.readableSource(minio.objectUrl(result.path("objectKey").asText())));
        return result;
    }
    private ObjectNode publicTask(JsonNode raw) {
        ObjectNode task=raw.deepCopy(); task.remove("ownerId");
        task.put("sourceDeleted",db.queryForObject("SELECT COUNT(*) FROM media_files WHERE id=? AND user_id=?",Long.class,raw.path("mediaId").asLong(),raw.path("ownerId").asLong())==0);
        return task;
    }
    private ObjectNode owned(String taskId,long owner,boolean lock) {
        var rows=db.query("SELECT snapshot FROM creation_tasks WHERE task_id=?"+(lock?" FOR UPDATE":""),(r,n)->read(r.getString(1)),taskId);
        if(rows.isEmpty()) throw error(ErrorCode.NOT_FOUND,"创作任务不存在");
        ObjectNode task=rows.get(0);
        if(task.path("ownerId").asLong()!=owner) throw error(ErrorCode.FORBIDDEN,"无权访问创作任务");
        return task;
    }
    private ObjectNode op(String id,boolean lock) {
        var rows=db.query("SELECT snapshot FROM creation_operations WHERE operation_id=?"+(lock?" FOR UPDATE":""),(r,n)->read(r.getString(1)),id);
        if(rows.isEmpty()) throw error(ErrorCode.NOT_FOUND,"创作操作不存在");
        return rows.get(0);
    }
    private void save(ObjectNode task) { db.update("UPDATE creation_tasks SET snapshot=? WHERE task_id=?",write(task),task.path("taskId").asText()); }
    private void source(JsonNode task) {
        media.requireOwnedMedia(task.path("mediaId").asLong(),task.path("ownerId").asLong());
        if(!version.equals(task.path("sourceVersion").asText()) || !ready(checkpoints.loadContext(task.path("mediaId").asLong()))) throw error(ErrorCode.CONFLICT,"素材解析版本已失效，请重新创建任务");
    }

    private ObjectNode scoped(String taskId,JsonNode request) {
        ObjectNode op=op(text(request,"operationId",64),false);
        ObjectNode task=owned(taskId,op.path("ownerId").asLong(),false);
        if(!taskId.equals(op.path("taskId").asText()) || !task.path("currentOperationId").asText().equals(op.path("operationId").asText())
                || request.path("attempt").asInt()!=op.path("attempt").asInt()
                || Set.of("FAILED","COMPLETED","AWAITING_CONFIRMATION","DISCUSSED").contains(task.path("status").asText())) throw error(ErrorCode.CONFLICT,"创作操作已失效");
        source(task); return task;
    }

    public List<JsonNode> search(String taskId,JsonNode request) {
        ObjectNode task=scoped(taskId,request);
        String query=text(request,"query",500);
        int limit=Math.max(1,Math.min(8,request.path("limit").asInt(5)));
        VideoContext context=checkpoints.loadContext(task.path("mediaId").asLong());
        return retrieval.searchEvidence(task.path("mediaId").asLong(),new VideoContext(context.source(),query,context.segments())).stream().limit(limit).map(h -> evidence(h.startMs(),h.endMs(),h.snippet(),h.transcript(),h.ocrTexts())).toList();
    }
    public List<JsonNode> context(String taskId,JsonNode request) {
        ObjectNode task=scoped(taskId,request);
        String id=text(request,"evidenceId",80);
        VideoContext context=checkpoints.loadContext(task.path("mediaId").asLong());
        var center=context.segments().stream().filter(s -> evidenceId(s.startMs(),s.endMs()).equals(id)).findFirst().orElseThrow(()->error(ErrorCode.INVALID_ARGUMENT,"证据不属于此素材版本"));
        int window=Math.max(0,Math.min(30000,request.path("windowMs").asInt(10000)));
        return context.segments().stream().filter(s -> s.endMs()>=center.startMs()-window && s.startMs()<=center.endMs()+window).limit(20)
                .map(s -> evidence(s.startMs(),s.endMs(),s.transcript(),s.transcript(),s.ocrTexts())).toList();
    }
    public JsonNode frame(String taskId,JsonNode request) {
        ObjectNode task=scoped(taskId,request);
        VideoContext context=checkpoints.loadContext(task.path("mediaId").asLong());
        String id=text(request,"evidenceId",80);
        if(!request.path("timestampMs").isIntegralNumber()) throw new IllegalArgumentException("timestampMs must be an integer");
        long timestamp=request.path("timestampMs").asLong();
        validateFrameTimestamp(context,id,timestamp);
        var file=media.requireOwnedMedia(task.path("mediaId").asLong(),task.path("ownerId").asLong());
        JsonNode result=json.valueToTree(frames.extract(media.readableSource(file.getFilePath()),timestamp));
        // A long extraction must not return data for an operation superseded in the meantime.
        scoped(taskId,request);
        return result;
    }

    static void validateFrameTimestamp(VideoContext context,String evidenceId,long timestamp) {
        if(timestamp<0 || context.segments().stream().noneMatch(s -> evidenceId(s.startMs(),s.endMs()).equals(evidenceId)
                && timestamp>=s.startMs() && timestamp<s.endMs())) throw new IllegalArgumentException("frame timestamp is outside bound video evidence");
    }

    private JsonNode evidence(long start,long end,String snippet,String transcript,List<String> ocr) {
        ObjectNode node=json.createObjectNode().put("evidenceId",evidenceId(start,end)).put("startMs",start).put("endMs",end)
                .put("snippet",clip(snippet,500)).put("transcript",clip(transcript,2000));
        node.set("ocrTexts",json.valueToTree(ocr.stream().filter(Objects::nonNull).limit(10).map(t->clip(t,300)).toList())); return node;
    }
    static String evidenceId(long start,long end) { return "e_"+start+"_"+end; }

    public void event(String taskId,JsonNode event) {
        tx.executeWithoutResult(s -> {
            ObjectNode operation=op(text(event,"operationId",64),false);
            ObjectNode task=owned(taskId,operation.path("ownerId").asLong(),true);
            operation=op(event.path("operationId").asText(),true);
            if(!taskId.equals(operation.path("taskId").asText()) || !task.path("currentOperationId").asText().equals(operation.path("operationId").asText())
                    || event.path("attempt").asInt()!=operation.path("attempt").asInt()) throw error(ErrorCode.CONFLICT,"旧创作操作结果已忽略");
            long seq=event.path("sequence").asLong(-1);
            if(!event.path("sequence").isIntegralNumber() || seq<1) throw new IllegalArgumentException("事件序号必须为正整数");
            if(seq<=operation.path("sequence").asLong()) return;
            if(Set.of("AWAITING_CONFIRMATION","COMPLETED","FAILED","DISCUSSED").contains(task.path("status").asText())) return;
            String status=text(event,"status",32), type=operation.path("type").asText();
            if(!status.equals("FAILED")) source(task);
            Set<String> allowed=switch(type) {
                case "CONVERSE" -> Set.of("DRAFTING","REVISING","DISCUSSED","AWAITING_CONFIRMATION","FAILED");
                case "CREATE" -> Set.of("DRAFTING","AWAITING_CONFIRMATION","FAILED");
                case "REVISE" -> Set.of("REVISING","AWAITING_CONFIRMATION","FAILED");
                case "RENDER" -> Set.of("RENDERING","COMPLETED","FAILED");
                default -> Set.of();
            };
            if(!allowed.contains(status)) throw error(ErrorCode.INVALID_ARGUMENT,"无效创作状态迁移");
            String reply=null;
            if(type.equals("CONVERSE") && Set.of("DISCUSSED","AWAITING_CONFIRMATION").contains(status)) {
                reply=validateReply(event,status);
                if(status.equals("AWAITING_CONFIRMATION")) {
                    boolean hasBoard=operation.path("parameters").path("storyboard").isObject();
                    if(hasBoard!=event.path("action").asText().equals("REVISE")) throw new IllegalArgumentException("对话动作与已有分镜不匹配");
                }
            }
            if(status.equals("AWAITING_CONFIRMATION")) {
                JsonNode board=event.path("storyboard");
                int revision=operation.path("parameters").path("baseRevision").asInt()+1;
                validateStoryboard(board,revision,checkpoints.loadContext(task.path("mediaId").asLong()));
                long total=0;
                for(JsonNode scene:board.path("scenes")) total+=scene.path("durationMs").asLong();
                if(total!=task.path("durationSeconds").asLong()*1000) throw new IllegalArgumentException("分镜总时长必须符合所选时长");
                validateTargets(operation.path("parameters"),board);
                task.set("storyboard",board); task.put("revision",revision);
            }
            if(status.equals("COMPLETED")) {
                JsonNode artifact=event.path("artifact"); int rev=task.path("revision").asInt();
                String expected="creations/"+taskId+"/"+rev+"/"+operation.path("operationId").asText()+"/output.mp4";
                if(!expected.equals(artifact.path("objectKey").asText()) || artifact.path("revision").asInt()!=rev
                        || artifact.path("sizeBytes").asLong()<=0 || artifact.path("durationMs").asLong()<30000 || artifact.path("durationMs").asLong()>61000
                        || !artifact.path("sha256").asText().matches("[a-fA-F0-9]{64}")
                        || operation.path("parameters").path("confirmedRevision").asInt()!=rev) throw error(ErrorCode.INVALID_ARGUMENT,"无效成片元数据");
                task.put("artifactRevision",rev);
                db.update("INSERT INTO creation_artifacts(task_id,revision,snapshot) VALUES(?,?,?) ON DUPLICATE KEY UPDATE snapshot=VALUES(snapshot)",taskId,rev,write(artifact));
            }
            if(reply!=null) appendMessage(task,operation.path("operationId").asText()+":assistant","assistant",reply);
            task.put("status",status).put("summary",clip(event.path("summary").asText(),500))
                    .put("error",status.equals("FAILED")?clip(event.path("error").asText(),1000):"")
                    .put("retryable",status.equals("FAILED") && event.path("retryable").asBoolean());
            operation.put("sequence",seq);
            boolean active=!Set.of("AWAITING_CONFIRMATION","COMPLETED","FAILED","DISCUSSED").contains(status);
            db.update("UPDATE creation_operations SET snapshot=?,active=?,updated_at=CURRENT_TIMESTAMP(3) WHERE operation_id=?",write(operation),active,operation.path("operationId").asText());
            save(task);
        });
    }

    static String validateReply(JsonNode event,String status) {
        String action=text(event,"action",16);
        if(status.equals("DISCUSSED") ? !action.equals("DISCUSS") : !Set.of("CREATE","REVISE").contains(action))
            throw new IllegalArgumentException("对话动作与状态不匹配");
        return text(event,"reply",4000);
    }

    private void appendMessage(ObjectNode task,String id,String role,String content) {
        var messages=json.createArrayNode();
        if(task.path("messages").isArray()) task.path("messages").forEach(messages::add);
        for(JsonNode message:messages) if(id.equals(message.path("messageId").asText())) return;
        messages.add(json.createObjectNode().put("messageId",id).put("role",role).put("content",clip(content,4000)));
        task.set("messages",messages);
    }

    static JsonNode boundedMessages(JsonNode source) {
        var result=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        int size=0;
        for(int i=source.size()-1;i>=0 && result.size()<20;i--) {
            JsonNode item=source.get(i);
            String content=clip(item.path("content").asText(),4000);
            if(size+content.length()>24000) break;
            ObjectNode copy=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("role",item.path("role").asText()).put("content",content);
            result.insert(0,copy); size+=content.length();
        }
        return result;
    }

    static void validateStoryboard(JsonNode board,int revision,VideoContext context) {
        if(!board.isObject() || board.path("schemaVersion").asInt()!=1 || board.path("revision").asInt()!=revision) throw new IllegalArgumentException("分镜版本无效");
        text(board,"title",80); text(board,"targetAudience",80);
        if(!Set.of("none","light_01").contains(board.path("musicId").asText())) throw new IllegalArgumentException("音乐不在白名单");
        JsonNode scenes=board.path("scenes");
        if(!scenes.isArray() || scenes.size()<3 || scenes.size()>8) throw new IllegalArgumentException("分镜必须包含 3～8 个场景");
        Set<String> refs=new HashSet<>(),ids=new HashSet<>();
        context.segments().forEach(s -> refs.add(evidenceId(s.startMs(),s.endMs())));
        long duration=0;
        for(JsonNode scene:scenes) {
            String id=text(scene,"sceneId",48);
            if(!id.matches("[A-Za-z0-9_-]+") || !ids.add(id)) throw new IllegalArgumentException("场景 ID 必须唯一");
            if(!Set.of("title","points","flow").contains(scene.path("template").asText()) || !Set.of("fade","reveal","highlight").contains(scene.path("animation").asText())) throw new IllegalArgumentException("场景模板或动画无效");
            text(scene,"headline",50);
            JsonNode visual=scene.path("visual");
            if(!visual.isMissingNode() && !visual.isNull()) {
                if(!visual.isObject() || !Set.of("pipeline","split","merge","compare","bars","orbit").contains(visual.path("layout").asText())
                    || !Set.of("dark","light","warm").contains(visual.path("theme").asText())
                    || !Set.of("slide","pulse","draw","reveal").contains(visual.path("motion").asText())) throw new IllegalArgumentException("图解配置无效");
                JsonNode items=visual.path("items");
                if(!items.isArray() || items.size()<2 || items.size()>4) throw new IllegalArgumentException("图解需要 2～4 个元素");
                for(JsonNode item:items) {
                    if(!Set.of("video","audio","text","agent","cards","phone","server","database","person","file").contains(item.path("icon").asText())) throw new IllegalArgumentException("图解物体无效");
                    text(item,"label",12);
                    if(!item.path("value").isIntegralNumber() || item.path("value").asInt()<0 || item.path("value").asInt()>100) throw new IllegalArgumentException("图解比例无效");
                }
            }
            long ms=scene.path("durationMs").asLong(); if(!scene.path("durationMs").isIntegralNumber() || ms<2000 || ms>30000) throw new IllegalArgumentException("场景时长无效"); duration+=ms;
            if(!scene.path("points").isArray() || scene.path("points").size()>4) throw new IllegalArgumentException("场景要点过多");
            for(JsonNode point:scene.path("points")) if(!point.isTextual() || point.asText().isBlank() || point.asText().length()>72) throw new IllegalArgumentException("场景要点过长");
            if(!scene.path("sourceRefs").isArray() || scene.path("sourceRefs").isEmpty() || scene.path("sourceRefs").size()>8) throw new IllegalArgumentException("缺少素材证据");
            for(JsonNode ref:scene.path("sourceRefs")) if(!refs.contains(ref.asText())) throw new IllegalArgumentException("证据不属于素材版本");
        }
        if(duration<30000 || duration>60000) throw new IllegalArgumentException("总时长必须为 30～60 秒");
    }

    static void validateTargets(JsonNode params,JsonNode board) {
        JsonNode targets=params.path("targetSceneIds");
        if(!targets.isArray() || targets.isEmpty()) return;
        Set<String> ids=new HashSet<>(); targets.forEach(id -> ids.add(id.asText()));
        JsonNode old=params.path("storyboard");
        for(String field:List.of("title","musicId","targetAudience")) if(!old.path(field).equals(board.path(field))) throw new IllegalArgumentException("局部修改改变了全局分镜字段");
        List<JsonNode> before=new ArrayList<>(),after=new ArrayList<>();
        old.path("scenes").forEach(scene -> { if(!ids.contains(scene.path("sceneId").asText())) before.add(scene); });
        board.path("scenes").forEach(scene -> { if(!ids.contains(scene.path("sceneId").asText())) after.add(scene); });
        if(!before.equals(after)) throw new IllegalArgumentException("局部修改改变了非目标场景");
    }

    @Scheduled(fixedDelayString="${creation.dispatch-delay-ms:5000}", scheduler="creationTaskScheduler")
    public void dispatch() {
        if(!enabled) return;
        var pending=db.queryForList("SELECT operation_id,dispatched,dispatch_failures,TIMESTAMPDIFF(SECOND,created_at,CURRENT_TIMESTAMP(3)) AS age_seconds FROM creation_operations WHERE active=TRUE AND updated_at <= CURRENT_TIMESTAMP(3)-INTERVAL 5 SECOND AND (dispatched=FALSE OR updated_at < CURRENT_TIMESTAMP(3)-INTERVAL 20 SECOND) ORDER BY updated_at LIMIT 5");
        for(var row:pending) {
            String id=(String)row.get("operation_id");
            ObjectNode operation=op(id,false);
            try {
                if(token.isBlank()) { fail(operation,"创作服务未配置 creation.internal-token"); continue; }
                if(((Number)row.get("age_seconds")).longValue()>1800) { fail(operation,"创作操作超时，请重试"); continue; }
                try { source(owned(operation.path("taskId").asText(),operation.path("ownerId").asLong(),false)); }
                catch(BusinessException | NoSuchElementException e) { fail(operation,"原素材已删除或解析版本失效，请新建任务"); continue; }
                // Query before re-submitting: a lost HTTP response must not create another execution.
                HttpResponse<String> response=http.send(HttpRequest.newBuilder(URI.create(python+"/internal/operations/"+id)).timeout(Duration.ofSeconds(5)).header("X-Creator-Token",token).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()==200) {
                    JsonNode result=json.readTree(response.body());
                    if(result.path("attempt").asInt()==operation.path("attempt").asInt() && result.path("event").isObject()) event(operation.path("taskId").asText(),result.path("event"));
                } else if(response.statusCode()==404) {
                    ObjectNode submission=operation.deepCopy(); submission.remove("sequence");
                    response=http.send(HttpRequest.newBuilder(URI.create(python+"/internal/operations")).timeout(Duration.ofSeconds(8)).header("X-Creator-Token",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(write(submission))).build(),HttpResponse.BodyHandlers.ofString());
                    if(response.statusCode()>=400 && response.statusCode()<500 && response.statusCode()!=429) { fail(operation,"创作服务拒绝了操作，请检查服务令牌和接口配置"); continue; }
                    if(response.statusCode()<200 || response.statusCode()>=300) throw new IllegalStateException("Creator rejected operation");
                } else if(response.statusCode()>=400 && response.statusCode()<500 && response.statusCode()!=429) { fail(operation,"创作服务认证或接口配置错误"); continue; }
                else throw new IllegalStateException("Creator unavailable");
                db.update("UPDATE creation_operations SET dispatched=TRUE,dispatch_failures=0,updated_at=CURRENT_TIMESTAMP(3) WHERE operation_id=?",id);
            } catch(Exception e) {
                if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("creation_dispatch_retry operationId={} errorType={}",id,e.getClass().getSimpleName());
                int failures=((Number)row.get("dispatch_failures")).intValue()+1;
                if(failures>=12) { try { fail(op(id,false),"创作服务持续不可用，请稍后重试"); } catch(RuntimeException ignored) { log.warn("creation_dispatch_failure_callback operationId={}",id); } }
                else db.update("UPDATE creation_operations SET dispatch_failures=?,updated_at=CURRENT_TIMESTAMP(3) WHERE operation_id=?",failures,id);
            }
        }
    }
    private void fail(ObjectNode operation,String message) {
        ObjectNode event=json.createObjectNode().put("operationId",operation.path("operationId").asText()).put("attempt",operation.path("attempt").asInt())
                .put("sequence",operation.path("sequence").asLong()+1).put("status","FAILED").put("summary",message).put("error",message).put("retryable",true);
        event(operation.path("taskId").asText(),event);
    }
    private void requireEnabled() { if(!enabled) throw error(ErrorCode.SERVICE_UNAVAILABLE,"创作服务未启用"); }
    private static void requestId(JsonNode request) { String id=text(request,"requestId",128); if(!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("requestId 格式无效"); }
    private static long positive(JsonNode request,String key) { long n=request.path(key).asLong(); if(n<=0) throw new IllegalArgumentException(key+" 无效"); return n; }
    static String text(JsonNode request,String key,int max) { JsonNode n=request.path(key); String v=n.asText("").trim(); if(!n.isTextual() || v.isBlank() || v.length()>max) throw new IllegalArgumentException(key+" 必填且长度不能超过 "+max); return v; }
    private static String clip(String value,int limit) { return value==null?"":value.substring(0,Math.min(limit,value.length())); }
    private static BusinessException error(ErrorCode code,String message) { return new BusinessException(code,message); }
    private ObjectNode read(String value) { try { return (ObjectNode)json.readTree(value); } catch(Exception e) { throw new IllegalStateException("Invalid persisted creation state",e); } }
    private String write(JsonNode value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid creation payload",e); } }
}
