package com.videotrace.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.videotrace.common.Result;
import com.videotrace.service.CreationService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Uses its own service authentication; never accepts user identity from a client. */
@RestController
@RequestMapping("/internal/creation/tasks/{taskId}")
public class InternalCreationController {
    private final CreationService service;
    public InternalCreationController(CreationService service) { this.service=service; }
    @PostMapping("/evidence-search")
    public Result<List<JsonNode>> search(@PathVariable String taskId,@RequestHeader(value="X-Creator-Token",required=false) String token,@RequestBody JsonNode body) { service.authenticate(token); return Result.ok(service.search(taskId,body)); }
    @PostMapping("/context")
    public Result<List<JsonNode>> context(@PathVariable String taskId,@RequestHeader(value="X-Creator-Token",required=false) String token,@RequestBody JsonNode body) { service.authenticate(token); return Result.ok(service.context(taskId,body)); }
    @PostMapping("/events")
    public Result<Void> event(@PathVariable String taskId,@RequestHeader(value="X-Creator-Token",required=false) String token,@RequestBody JsonNode body) { service.authenticate(token); service.event(taskId,body); return Result.ok(); }
}
