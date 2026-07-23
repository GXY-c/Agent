<template>
  <div class="sse-log-panel">
    <div class="log-toggle" @click="expanded = !expanded">
      <span>{{ expanded ? '▼' : '▶' }} 原始 SSE 日志 ({{ logs.length }})</span>
    </div>
    <div v-if="expanded" class="log-body" ref="logBodyRef">
      <div v-for="(entry, idx) in logs" :key="idx" class="log-entry">
        <span class="log-time">{{ entry.time }}</span>
        <span class="log-type" :class="'type-' + entry.type">{{ entry.type }}</span>
        <span class="log-data">{{ entry.data }}</span>
      </div>
      <div v-if="logs.length === 0" class="log-empty">暂无日志</div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue';

const props = defineProps({
  logs: { type: Array, default: () => [] }
});

const expanded = ref(false);
const logBodyRef = ref(null);

watch(() => props.logs.length, () => {
  if (expanded.value) {
    nextTick(() => {
      if (logBodyRef.value) {
        logBodyRef.value.scrollTop = logBodyRef.value.scrollHeight;
      }
    });
  }
});
</script>

<style scoped>
.sse-log-panel {
  border-top: 1px solid #e0e0e0;
  background: #1e293b;
  border-radius: 0 0 12px 12px;
  margin-top: 8px;
}
.log-toggle {
  padding: 8px 14px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  color: #94a3b8;
  user-select: none;
}
.log-toggle:hover {
  color: #e2e8f0;
}
.log-body {
  max-height: 200px;
  overflow-y: auto;
  padding: 0 14px 10px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 11px;
  scrollbar-width: thin;
}
.log-entry {
  display: flex;
  gap: 8px;
  padding: 2px 0;
  line-height: 1.4;
  word-break: break-all;
}
.log-time {
  color: #64748b;
  flex-shrink: 0;
}
.log-type {
  font-weight: 700;
  flex-shrink: 0;
  min-width: 100px;
}
.type-thinking { color: #a78bfa; }
.type-action_start { color: #fbbf24; }
.type-action_complete { color: #34d399; }
.type-needs_input { color: #fb923c; }
.type-done { color: #4ade80; }
.type-error { color: #f87171; }
.log-data {
  color: #cbd5e1;
}
.log-empty {
  color: #64748b;
  padding: 10px 0;
}
</style>
