package io.duo.sim.embedded.resource;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;

import java.util.List;
import java.util.Map;

/**
 * resource 契约的 embedded 档实现（M2 T28）：**Fabric8 KubernetesMockServer**——
 * 本地内存 HTTP 服务，拦截 K8s API 请求（创建 Pod、查询、监听事件），
 * 不需要 Minikube 或真实集群（与讨论中 KWOK/Fabric8 mock 的定位一致）。
 *
 * <p>端点形态 THIRD_PARTY：{@code endpoints()} 暴露 mock server 的 HTTP 端点，
 * 供 K8s 类 SUT（或 Fabric8 客户端）连真实的 K8s REST 协议。
 * 可观测：{@code sim.resource-started}，以及 Pod 创建/删除经客户端操作后由 SUT 侧旁路观察。
 */
public final class Fabric8K8sMock implements VirtualComponent {

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile KubernetesMockServer server;
    private volatile KubernetesClient client;
    private volatile boolean running;

    @Override
    public ComponentId id() {
        return id;
    }

    @Override
    public void init(ComponentContext ctx) {
        this.ctx = ctx;
        this.id = ctx.id();
    }

    @Override
    public void start() throws ComponentException {
        try {
            // 非 CRUD 模式 + 显式 expect：调用方通过 expect() 声明期望的 API 交互
            // （Fabric8 mock 6.x 的 CRUD store 对 Pod 等资源未自动注册路径）
            server = new KubernetesMockServer(false);
            server.init();
            client = server.createClient();
            running = true;
            fire(Event.sim("sim.resource-started", id.value(),
                    Map.of("kind", "k8s-mock", "url", server.url("/").toString())));
        } catch (RuntimeException e) {
            throw new ComponentException("cannot start Fabric8 K8s mock: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop(StopMode mode) {
        running = false;
        try {
            if (server != null) {
                server.destroy();
            }
        } catch (RuntimeException ignored) {
            // 尽力而为
        }
        fire(Event.sim(mode == StopMode.CRASH ? "sim.resource-crashed"
                : "sim.resource-stopped", id.value(), Map.of()));
    }

    @Override
    public void restart() {
        stop(StopMode.GRACEFUL);
        start();
        fire(Event.sim("sim.resource-restarted", id.value(), Map.of()));
    }

    @Override
    public HealthReport health() {
        return running && server != null
                ? HealthReport.ok() : HealthReport.down("k8s mock not running");
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        return running && server != null
                ? List.of(new ExposedEndpoint(io.duo.sim.kernel.api.Contract.RESOURCE,
                        "http", server.url("/").toString()))
                : List.of();
    }

    /** 供测试/SUT 使用的 Fabric8 客户端。 */
    public KubernetesClient client() {
        return client;
    }

    /** mock server 的 URL（诊断/测试）。 */
    public String url() {
        return server == null ? null : server.url("/").toString();
    }

    /** 期望交互声明入口（非 CRUD 模式：Pod CRUD 等需显式 expect）。 */
    public KubernetesMockServer server() {
        return server;
    }

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
