<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import { session } from '../stores/session'

const router = useRouter()
const token = ref('')
const error = ref('')

async function login() {
  error.value = ''
  session.login(token.value.trim())
  try {
    // /health 免令牌——探测服务在；/api/meta 验令牌并取认证模式
    await api.health()
    const meta = await api.meta()
    session.authMode = meta.auth
    router.push('/library')
  } catch (e: any) {
    session.logout()
    error.value = e?.message ?? '登录失败'
  }
}
</script>

<template>
  <div class="login-page">
    <h1>Duo Console</h1>
    <p class="hint">输入控制面令牌（<code>duo serve --token …</code> 启动时设定）</p>
    <form @submit.prevent="login">
      <input v-model="token" type="password" placeholder="Bearer 令牌" autofocus />
      <button type="submit">进入控制台</button>
    </form>
    <p v-if="error" class="error">{{ error }}</p>
  </div>
</template>

<style scoped>
.login-page { max-width: 420px; margin: 15vh auto; text-align: center; }
.login-page input { width: 100%; padding: 10px; margin: 12px 0; box-sizing: border-box; }
.login-page button { padding: 10px 24px; cursor: pointer; }
.error { color: #c0392b; }
.hint { color: #666; font-size: 13px; }
</style>
