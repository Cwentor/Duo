package io.duo.sim.kernel.api;

/** 停止模式（§7.1）：CRASH 即故障注入的"宕机"——不发停止事件、不优雅收尾。 */
public enum StopMode {
    GRACEFUL, CRASH
}
