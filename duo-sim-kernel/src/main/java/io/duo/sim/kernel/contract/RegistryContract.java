package io.duo.sim.kernel.contract;

import io.duo.sim.kernel.api.EndpointShape;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * registry 契约（§5：协调中心——会话、临时节点、watch）。
 *
 * <p>必发事件：会话建立/断开（{@code sim.registry-session-opened/closed} 由实现经事件总线发）、
 * 节点变更（临时节点创建/删除经 watch 回调）。
 * 可观测点：会话数、临时节点数；external SUT 的"重选主"由本契约临时节点变更旁路推断（§7.3）。
 * 配置项：M0 无（内存状态机）。
 *
 * <p>M0 virtual 档＝VirtualRegistry：端点形态 NONE、interface-direct 注入本接口
 * （含 SUT master 注册自身端点、workers 发现 master 的查询 API，计划 §2）。
 *
 * <p><b>两档 flap 语义差异（M2 计划 D3）</b>——同一动作类型在不同档位的真实语义不同，
 * 消费方（SUT）必须知道：
 * <ul>
 *   <li><b>virtual 档</b>：flap 后**端点快照重放**——临时节点自动重建，对消费方透明
 *       （M1 T18）；且 flap 是**持续窗口**，`FaultAction.durationMillis` 有效（窗口内
 *       发现为空，窗口结束才重放）；</li>
 *   <li><b>embedded 档</b>：flap 是**真实会话与临时节点丢失**（TestingServer 整服重启，
 *       瞬时完成）——任何消费方都必须自行重连并重新注册，框架不代劳（M2 T25）。
 *       **注意：embedded 的 flap 是瞬时动作，`durationMillis` 被忽略**（无持续窗口；
 *       消费方的恢复耗时由其自身重连策略决定，而非框架强制的窗口）。</li>
 * </ul>
 */
public interface RegistryContract {

    /** 开启会话（临时节点挂靠其下；会话断开＝其临时节点全部消失）。 */
    RegistrySession openSession(String sessionId);

    /** 端点注册与发现（M0：master 写临时节点 /duo/endpoints/&lt;contract&gt; 载荷 host:port）。 */
    void registerEndpoint(String contract, String address);

    /** 发现查询：返回当前注册的端点地址；未注册返回空列表。 */
    List<String> discoverEndpoints(String contract);

    /** watch 节点变更（临时节点创建/删除都会触发）。 */
    void watch(String path, Consumer<RegistryChange> listener);

    /** 端点形态约定（供能力元数据一致性校验核对）。 */
    EndpointShape declaredShape();

    record RegistryChange(String path, String value, ChangeKind kind) {
        public enum ChangeKind { CREATED, DELETED }
    }

    /** 会话句柄。 */
    interface RegistrySession {

        /** 创建临时节点（ephemeral）。 */
        void createEphemeral(String path, String value);

        /** 删除节点。 */
        void delete(String path);

        /** 关闭会话（其临时节点全部清理）。 */
        void close();

        /** 会话是否存活。 */
        boolean isAlive();
    }

    /** M0 支持的已知节点路径（供校验与实现共享）。 */
    Set<String> KNOWN_PATHS = Set.of("/duo/endpoints/scheduler", "/duo/workers");
}
