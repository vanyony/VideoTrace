package com.videotrace.exception;

import com.videotrace.common.ErrorCode;
import java.util.concurrent.RejectedExecutionException;

public final class ResourceBusyException extends BusinessException {
    public ResourceBusyException() { super(ErrorCode.SERVICE_UNAVAILABLE,"处理资源繁忙，请稍后重试"); }
    public static boolean causedBy(Throwable error) {
        for(int i=0;error!=null && i<16;i++,error=error.getCause())
            if(error instanceof ResourceBusyException || error instanceof RejectedExecutionException) return true;
        return false;
    }
}
