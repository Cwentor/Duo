/**
 * resource 契约的 embedded 档（M2 T28）：{@link Fabric8K8sMock}——Fabric8 KubernetesMockServer，
 * 本地内存 HTTP 服务拦截 K8s API 请求（创建 Pod、查询、监听），表达「真实 K8s REST 协议的
 * API 交互」；配额分配/耗尽语义由 virtual 档（{@code VirtualResourceManager}）承载——
 * 两个档位互补，不互相替代。
 */
package io.duo.sim.embedded.resource;
