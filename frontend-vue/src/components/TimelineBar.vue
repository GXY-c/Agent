<template>
  <div class="timeline-bar" v-if="steps.length > 0">
    <div class="timeline-track" ref="trackRef">
      <div
          v-for="(step, idx) in timelineItems"
          :key="idx"
          class="timeline-node"
          :class="step.statusClass"
          :title="step.tooltip"
      >
        <div class="node-dot">
          <span>{{ step.icon }}</span>
        </div>
        <div class="node-label">{{ step.label }}</div>
        <div class="node-time" v-if="step.duration !== null">{{ step.duration }}s</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, nextTick } from 'vue';

const props = defineProps({
  steps: { type: Array, default: () => [] }
});

const trackRef = ref(null);

const timelineItems = computed(() => {
  const items = [];
  const actionSteps = props.steps.filter(s => s.type === 'result' || s.type === 'action' || s.type === 'done' || s.type === 'error');
  for (let i = 0; i < actionSteps.length; i++) {
    const s = actionSteps[i];
    const prev = i > 0 ? actionSteps[i - 1] : null;
    const duration = prev ? Math.round((s.timestamp - prev.timestamp) / 1000) : null;

    let icon = '⚡';
    let statusClass = 'running';
    if (s.type === 'result') {
      icon = s.success ? '✅' : '❌';
      statusClass = s.success ? 'success' : 'failed';
    } else if (s.type === 'done') {
      icon = s.success ? '🎉' : '❌';
      statusClass = s.success ? 'success' : 'failed';
    } else if (s.type === 'error') {
      icon = '❌';
      statusClass = 'failed';
    } else if (s.type === 'action') {
      icon = '⏳';
      statusClass = 'running';
    }

    items.push({
      icon,
      statusClass,
      label: `S${s.step || i + 1}`,
      tooltip: s.content || '',
      duration
    });
  }
  return items;
});

watch(() => timelineItems.value.length, () => {
  nextTick(() => {
    if (trackRef.value) {
      trackRef.value.scrollLeft = trackRef.value.scrollWidth;
    }
  });
});
</script>

<style scoped>
.timeline-bar {
  padding: 8px 0;
  border-bottom: 1px solid #e0e0e0;
  background: #f8fafc;
}
.timeline-track {
  display: flex;
  gap: 4px;
  overflow-x: auto;
  padding: 4px 12px;
  scrollbar-width: thin;
}
.timeline-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 48px;
  flex-shrink: 0;
  position: relative;
}
.node-dot {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  background: #e0e0e0;
  border: 2px solid #ccc;
}
.timeline-node.success .node-dot {
  background: #d1fae5;
  border-color: #16a34a;
}
.timeline-node.failed .node-dot {
  background: #fee2e2;
  border-color: #dc2626;
}
.timeline-node.running .node-dot {
  background: #fff3cd;
  border-color: #f59e0b;
  animation: pulse 1.5s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
.node-label {
  font-size: 10px;
  color: #64748b;
  margin-top: 2px;
  font-weight: 600;
}
.node-time {
  font-size: 9px;
  color: #94a3b8;
}
</style>
