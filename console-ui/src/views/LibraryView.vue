<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, ScenarioEntry, ApiError } from '../api/client'
import { forkTargetId } from './library-logic'

const router = useRouter()
const templates = ref<ScenarioEntry[]>([])
const user = ref<ScenarioEntry[]>([])
const error = ref('')
const busy = ref(false)

const allIds = computed(() => [...templates.value, ...user.value].map((s) => s.id))

onMounted(refresh)

async function refresh() {
  error.value = ''
  try {
    const r = await api.listScenarios()
    templates.value = r.templates
    user.value = r.user
  } catch (e: any) {
    if (!(e instanceof ApiError && e.status === 401)) error.value = String(e?.message ?? e)
  }
}

async function fork(s: ScenarioEntry) {
  busy.value = true
  error.value = ''
  try {
    const r = await api.forkScenario(s.id, { id: forkTargetId(s.id, allIds.value) })
    router.push(`/workspace/${encodeURIComponent(r.id)}`)
  } catch (e: any) {
    error.value = String(e?.message ?? e)
  } finally {
    busy.value = false
  }
}

async function remove(s: ScenarioEntry) {
  if (!window.confirm(`删除场景 ${s.id}？`)) return
  error.value = ''
  try {
    await api.deleteScenario(s.id)
    await refresh()
  } catch (e: any) {
    error.value = String(e?.message ?? e)
  }
}

function open(s: ScenarioEntry) {
  router.push(`/workspace/${encodeURIComponent(s.id)}`)
}
</script>

<template>
  <main class="library">
    <h1>场景库</h1>
    <p v-if="error" class="error">{{ error }}</p>

    <h2>内置模板（只读）</h2>
    <div class="cards">
      <div v-for="s in templates" :key="s.id" class="card">
        <h3>{{ s.name }}</h3>
        <p>{{ s.nodeCount }} 个节点</p>
        <button :disabled="busy" @click="fork(s)">Fork 到我的库</button>
        <button @click="open(s)">查看 YAML</button>
      </div>
    </div>

    <h2>我的场景</h2>
    <p v-if="user.length === 0" class="empty">还没有场景——从上面的模板 Fork 起步。</p>
    <div class="cards">
      <div v-for="s in user" :key="s.id" class="card">
        <h3>{{ s.name }}</h3>
        <p>{{ s.nodeCount }} 个节点 · {{ s.valid ? '可运行' : '校验不过' }}</p>
        <button :disabled="busy" @click="open(s)">打开编辑器</button>
        <button class="danger" :disabled="busy" @click="remove(s)">删除</button>
      </div>
    </div>
  </main>
</template>

<style scoped>
.library { max-width: 960px; margin: 0 auto; padding: 24px; }
.cards { display: flex; flex-wrap: wrap; gap: 12px; }
.card { border: 1px solid #ccc; border-radius: 8px; padding: 12px 16px; width: 260px; }
.card button { margin-right: 6px; cursor: pointer; }
.danger { color: #c0392b; }
.error { color: #c0392b; }
.empty { color: #888; }
</style>
