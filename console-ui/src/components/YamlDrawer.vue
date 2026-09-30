<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { basicSetup } from 'codemirror'
import { EditorView } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { yaml } from '@codemirror/lang-yaml'

const props = defineProps<{ text: string; readonly: boolean }>()
const emit = defineEmits<{ apply: [text: string] }>()

const host = ref<HTMLElement | null>(null)
let view: EditorView | null = null
let innerText = props.text

onMounted(() => {
  if (!host.value) return
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.text,
      extensions: [
        basicSetup,
        yaml(),
        EditorView.editable.of(!props.readonly),
        EditorView.updateListener.of((u) => {
          if (u.docChanged) innerText = u.state.doc.toString()
        }),
      ],
    }),
  })
})

onBeforeUnmount(() => view?.destroy())

// 外部重投影（画布改动/切换场景）时同步编辑器内容，但不动用户正在输入的文本
watch(() => props.text, (t) => {
  if (view && t !== innerText) {
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: t } })
    innerText = t
  }
})
</script>

<template>
  <div class="yaml-drawer">
    <div ref="host" class="editor-host" />
    <div class="actions">
      <button :disabled="props.readonly" @click="emit('apply', innerText)">
        应用到模型（YAML 是唯一事实来源）
      </button>
    </div>
  </div>
</template>

<style scoped>
.yaml-drawer { display: flex; flex-direction: column; height: 100%; }
.editor-host { flex: 1; overflow: auto; border: 1px solid #ccc; }
.editor-host :deep(.cm-editor) { height: 100%; }
.actions { padding: 6px; }
</style>
