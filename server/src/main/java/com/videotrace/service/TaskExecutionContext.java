package com.videotrace.service;

import java.util.function.Consumer;

/** Scope is installed only on the synchronous MQ worker thread, never inherited by pool threads. */
public final class TaskExecutionContext {
    private static final ThreadLocal<Consumer<Runnable>> CURRENT=new ThreadLocal<>();
    private TaskExecutionContext() {}
    static void bind(Consumer<Runnable> guard) { CURRENT.set(guard); }
    static Consumer<Runnable> capture() { return CURRENT.get(); }
    static void clear() { CURRENT.remove(); }
    public static boolean active() { return CURRENT.get()!=null; }
    public static void guarded(Runnable operation) {
        Consumer<Runnable> guard=CURRENT.get();
        if (guard==null) operation.run(); else guard.accept(operation);
    }
    public static void check() { guarded(() -> {}); }
    public static final class LeaseLostException extends IllegalStateException {
        public LeaseLostException() { super("Analysis execution lease is no longer valid"); }
    }
}
