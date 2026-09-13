package io.duo.sim.kernel.api;

/** 组件标识（拓扑节点 id 解析后内核分配的唯一 id）。 */
public record ComponentId(String value) {

    public ComponentId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("component id must not be blank");
        }
    }

    /** 实例级 sourceId 约定（§7.4）：{@code componentId-N}，如 workers-3。 */
    public String instanceSourceId(int index) {
        if (index < 1) {
            throw new IllegalArgumentException("instance index is 1-based, got " + index);
        }
        return value + "-" + index;
    }
}
