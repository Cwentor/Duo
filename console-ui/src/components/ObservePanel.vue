<script setup lang="ts">
import { computed, ref } from 'vue'
import { runtime } from '../stores/runtime'
import { api, DiagnoseReport } from '../api/client'

const tab = ref<'events' | 'assertions' | 'diagnose'>('events')
const report = ref<DiagnoseReport | null>(null)
const diagError = ref('')

const recentEvents = computed(() => runtime.events.slice(-200))
const assertions = computed(() => runtime.status?.assertions ?? [])

async function refreshDiagnose() {
  diagError.value = ''
  try {
    report.value = await api.diagnose()
  } catch (e: any) {
    diagError.value = String(e?.message ?? e)
  }
}
</script>

<template>
  <section class="observe">
    <nav>
      <button :class="{ active: tab === 'events' }" @click="tab = 'events'">事件流</button>
      <button :class="{ active: tab === 'assertions' }" @click="tab = 'assertions'">断言</button>
      <button :class="{ active: tab === 'diagnose' }" @click="refreshDiagnose(); tab = 'diagnose'">
        诊断链
      </button>
    </nav>

    <div v-if="tab === 'events'" class="body">
      <p class="meta">{{ runtime.events.length }} 条 · next={{ runtime.next }}
        <span v-if="runtime.dropped">· 已丢弃 {{ runtime.dropped }} 条</span>
      </p>
      <div v-for="e in recentEvents" :key="e.seq" class="row">
        <span class="seq">#{{ e.seq }}</span>
        <span class="type">{{ e.type }}</span>
        <span class="src">{{ e.sourceId }}</span>
      </div>
    </div>

    <div v-else-if="tab === 'assertions'" class="body">
      <p v-if="assertions.length === 0" class="meta">尚无断言结果（场景收口后可见）</p>
      <div v-for="a in assertions" :key="a.name" class="row">
        <span :class="a.passed ? 'pass' : 'fail'">{{ a.passed ? 'PASS' : 'FAIL' }}</span>
        <span>{{ a.name }}</span>
        <span class="detail">{{ a.detail }}</span>
      </div>
    </div>

    <div v-else class="body">
      <p v-if="diagError" class="fail">{{ diagError }}</p>
      <template v-if="report">
        <p class="meta">事件总数 {{ report.eventsTotal }} · 注入链 {{ report.chains.length }} 条</p>
        <div v-for="(c, i) in report.chains" :key="i" class="chain">
          <p>
            [{{ i + 1 }}] {{ c.action }} → {{ c.target }}
            <span v-if="c.failedReason" class="fail">{{ c.failedReason }}</span>
          </p>
          <p class="meta">组件反应 {{ c.componentReactions.length }} · SUT 事实 {{ c.sutFactTotal }}</p>
        </div>
        <p v-if="report.gaps.length" class="fail">断链（MISSING）：{{ report.gaps.join('; ') }}</p>
      </template>
    </div>
  </section>
</template>

<style scoped>
.observe { border-top: 1px solid #ccc; max-height: 36vh; display: flex; flex-direction: column; }
.observe nav { padding: 6px 16px 0; }
.observe nav button { margin-right: 8px; cursor: pointer; border: none; background: none; padding: 4px 2px; }
.observe nav .active { font-weight: bold; border-bottom: 2px solid #4a90d9; }
.body { overflow: auto; padding: 8px 16px; font-size: 12px; flex: 1; }
.row { display: flex; gap: 10px; }
.seq { color: #888; width: 52px; flex-shrink: 0; }
.type { color: #4a90d9; }
.pass { color: #2e8b57; font-weight: bold; }
.fail { color: #c0392b; }
.meta { color: #888; }
.detail { color: #666; }
</style>
