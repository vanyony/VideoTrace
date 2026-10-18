package com.videotrace.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.videotrace.common.Result;
import com.videotrace.service.AuthService;
import com.videotrace.service.CreationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/creation")
public class CreationController {
    private final CreationService service;
    public CreationController(CreationService service) { this.service=service; }
    @GetMapping("/eligibility")
    public Result<JsonNode> eligibility(@RequestParam long mediaId,@RequestAttribute(AuthService.REQUEST_USER_ID) long owner) { return Result.ok(service.eligibility(owner,mediaId)); }
    @GetMapping("/tasks")
    public Result<List<JsonNode>> list(@RequestParam long mediaId,@RequestAttribute(AuthService.REQUEST_USER_ID) long owner) { return Result.ok(service.list(owner,mediaId)); }
    @PostMapping("/tasks")
    public ResponseEntity<Result<JsonNode>> create(@RequestBody JsonNode body,@RequestAttribute(AuthService.REQUEST_USER_ID) long owner) { return ResponseEntity.accepted().body(Result.ok(service.create(owner,body))); }
    @PostMapping("/conversations")
    public ResponseEntity<Result<JsonNode>> conversation(@RequestBody JsonNode body,@RequestAttribute(AuthService.REQUEST_USER_ID) long owner) { return ResponseEntity.accepted().body(Result.ok(service.createConversation(owner,body))); }
    @PostMapping("/tasks/{id}/messages")
    public ResponseEntity<Result<JsonNode>> message(@PathVariable String id,@RequestBody JsonNode body,@RequestAttribute(AuthService.REQUEST_USER_ID) long owner) { return ResponseEntity.accepted().body(Result.ok(service.message(owner,id,body))); }
    @GetMapping("/tasks/{id}")
    public Result<JsonNode> get(@PathVariable String id,@RequestAttribute(AuthService.REQUEST_USER_ID) long owner) { return Result.ok(service.get(owner,id)); }
    @PostMapping("/tasks/{id}/revisions")
    public ResponseEntity<Result<JsonNode>> revise(@PathVariable String id,@RequestBody JsonNode body,@RequestAttribute(AuthService.REQUEST_USER_ID) long owner) { return accepted(owner,id,"REVISE",body); }
    @PostMapping("/tasks/{id}/render")
    public ResponseEntity<Result<JsonNode>> render(@PathVariable String id,@RequestBody JsonNode body,@RequestAttribute(AuthService.REQUEST_USER_ID) long owner) { return accepted(owner,id,"RENDER",body); }
    @PostMapping("/tasks/{id}/retry")
    public ResponseEntity<Result<JsonNode>> retry(@PathVariable String id,@RequestBody JsonNode body,@RequestAttribute(AuthService.REQUEST_USER_ID) long owner) { return accepted(owner,id,"RETRY",body); }
    @GetMapping("/tasks/{id}/artifact")
    public Result<JsonNode> artifact(@PathVariable String id,@RequestParam(required=false) Integer revision,@RequestAttribute(AuthService.REQUEST_USER_ID) long owner) { return Result.ok(service.artifact(owner,id,revision)); }
    private ResponseEntity<Result<JsonNode>> accepted(long owner,String id,String type,JsonNode body) { return ResponseEntity.accepted().body(Result.ok(service.change(owner,id,type,body))); }
}
