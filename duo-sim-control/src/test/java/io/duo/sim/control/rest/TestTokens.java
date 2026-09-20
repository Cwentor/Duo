package io.duo.sim.control.rest;

import java.net.URI;
import java.net.http.HttpRequest;

/**
 * 控制面测试的令牌辅助（安全审计 2026-09-20 之后：REST 控制面一律要求 Bearer 令牌）。
 *
 * <p>放在测试源码里而不是 {@code RestControlServer} 的 API 上：令牌是**运行期**注入的凭据，
 * 主代码不应该有"测试用默认令牌"这种后门（有后门就等于没有认证）。
 */
public final class TestTokens {

    /** 测试用令牌（每次 JVM 运行随机，不落任何文件）。 */
    public static final String TOKEN = java.util.UUID.randomUUID().toString();

    private TestTokens() {
    }

    /** 带认证头的请求构造器（URI 已就绪，供 GET/POST/DELETE 继续链式设置）。 */
    public static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + TOKEN);
    }
}
