package io.duo.sim.scenario;

import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.core.WiringResolver;

/** ScenarioValidator 对交互型契约档位的静态检查（§6 形态不存在）。 */
final class InteractiveTierGuard {

    private InteractiveTierGuard() {
    }

    /** 交互型契约声明 embedded/container → 拒绝（返回 true=违规）。 */
    static boolean rejects(String contract, Tier tier) {
        return Contract.fromYaml(contract).isInteractive()
                && (tier == Tier.EMBEDDED || tier == Tier.CONTAINER);
    }

    static boolean rejects(String contract, WiringResolver.TierView tier) {
        return rejects(contract, Tier.valueOf(tier.name()));
    }
}
