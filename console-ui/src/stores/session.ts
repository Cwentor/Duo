import { reactive } from 'vue'

const PERSIST_KEY = 'duo-console-token'

/** 会话 store：令牌只存 sessionStorage（关标签页即失效，规格 §7.2——不支持 URL 带 token）。 */
export const session = reactive({
  token: sessionStorage.getItem(PERSIST_KEY),
  authMode: null as 'TOKEN' | 'INSECURE' | null,
  login(token: string) {
    this.token = token
    sessionStorage.setItem(PERSIST_KEY, token)
  },
  logout() {
    this.token = null
    sessionStorage.removeItem(PERSIST_KEY)
  },
  tokenInSession() {
    return sessionStorage.getItem(PERSIST_KEY)
  },
})
