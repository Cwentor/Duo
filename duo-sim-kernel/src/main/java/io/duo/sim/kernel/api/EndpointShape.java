package io.duo.sim.kernel.api;

/** 端点形态（§7.5 能力元数据）：启动前校验只读该静态事实，不调运行时 endpoints()。 */
public enum EndpointShape {
    /** 无端点（进程内状态机）。 */
    NONE,
    /** 框架自定义 Duo 线协议端口。 */
    DUO_PORT,
    /** 真实第三方协议端口（如 ZK 2181、HTTP）。 */
    THIRD_PARTY,
    /** 文件系统路径（如 filestore 本地 FS 桩）。 */
    FS_PATH
}
