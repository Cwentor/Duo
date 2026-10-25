/**
 * registry 契约的「真实 ZK 后端」实现族（M2 T24 / M4 T38）：{@link ZkBackedRegistry} 双面骨架
 * （wire 面＝真实 ZK 端口供 SUT 客户端连接；门面面＝{@code RegistryContract} 转发真实协议往返），
 * 子类只负责更换 ZK 后端进程——{@link CuratorRegistry}（JVM 内 Curator TestingServer，
 * embedded 档，支持同端口重启式 flap）与 {@link ZookeeperContainerRegistry}
 * （Testcontainers 真容器，container 档，不支持 flap/restart——重启换宿主端口）。
 */
package io.duo.sim.embedded.registry;
