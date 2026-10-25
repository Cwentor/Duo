/**
 * resource 契约的 virtual 档（M5 交付物 2）：{@link VirtualResourceManager}——同进程资源配额替身
 * （CPU/内存配额的分配与释放），使「资源不足 → 任务被拒」这条路径在无 Docker 的常规回归里
 * 可演练；{@code resource-exhaust} 注入后全部新分配被显式拒绝。与 embedded 档
 * （真实 K8s REST）互补而非替代——两个档位表达不同的东西。
 */
package io.duo.sim.components.resource;
