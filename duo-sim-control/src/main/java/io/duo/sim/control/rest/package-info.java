/**
 * 控制面 REST 服务（M3 T33）：{@link RestControlServer}（JDK 内置 HttpServer + 虚拟线程，
 * 决策 D1 不引 Web 框架；端点声明与生命周期）+ {@link RestRoutes}（端点管道——认证/跨站校验/
 * 方法检查/请求体上限/异常→错误映射/消息掩码只发生一次，安全审计四层防线的执行点）+
 * {@link HttpCall}（业务分支的读写上下文与守卫式中止）与 {@link BodyTooLargeException}（413）。
 * 控制台专属面收在 {@code /api/} 前缀（M10），SPA 静态托管在 {@code /console/**}。
 */
package io.duo.sim.control.rest;
