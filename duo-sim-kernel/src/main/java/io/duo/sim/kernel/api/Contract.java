package io.duo.sim.kernel.api;

/** 内核认识的角色契约（设计文档 §5）。M0 反推 registry/worker/scheduler + engine 骨架。 */
public enum Contract {
    REGISTRY, WORKER, SCHEDULER, ENGINE,
    /** store/message/filestore/resource 为后续阶段预留。 */
    STORE, MESSAGE, FILESTORE, RESOURCE;

    /** 交互型契约（worker/engine/scheduler）：virtual 档暴露 Duo 线协议端口。 */
    public boolean isInteractive() {
        return this == WORKER || this == ENGINE || this == SCHEDULER;
    }

    public static Contract fromYaml(String name) {
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown contract: " + name, e);
        }
    }
}
