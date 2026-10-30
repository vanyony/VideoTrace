package com.videotrace.config;

import com.videotrace.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebConfigTest {
    static class Registry extends InterceptorRegistry {
        List<Object> entries() { return getInterceptors(); }
    }
    @Test void renamedMediaProcessingAndCreationEndpointsRemainAuthenticated() throws Exception {
        var auth=mock(AuthService.class);
        when(auth.resolveUser(null)).thenThrow(new SecurityException("请登录"));
        var interceptor=new AuthInterceptor(auth,new ObjectMapper());
        var registry=new Registry();
        new WebConfig(interceptor,"http://localhost:5173").addInterceptors(registry);
        var mapped=(MappedInterceptor)registry.entries().getFirst();
        for(String path:List.of("/media-processing/transcribe","/media-processing/download","/creation/prepare-source","/creation/source-status")) {
            var request=new MockHttpServletRequest("GET",path);
            ServletRequestPathUtils.parseAndCache(request);
            assertTrue(mapped.matches(request),path);
            var response=new MockHttpServletResponse();
            assertFalse(mapped.preHandle(request,response,new Object()));
            assertEquals(401,response.getStatus(),path);
        }
    }
}
