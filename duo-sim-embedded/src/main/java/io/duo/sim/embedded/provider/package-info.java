/**
 * embedded / container 档的 {@code ComponentProvider} 注册面：(REGISTRY, EMBEDDED) 与
 * (REGISTRY, CONTAINER)、(STORE, EMBEDDED) 与 (STORE, CONTAINER)、(RESOURCE, EMBEDDED)
 * 共五个实现。各 Provider 的静态元数据声明端点形态（真实 ZK/JDBC/K8s 端口供 SUT wire 连接）
 * 与能力边界（如 container 档 {@code supportedFaults=∅}、不支持 restart——校验期即拒绝，
 * §7.2 无降级）。
 */
package io.duo.sim.embedded.provider;
