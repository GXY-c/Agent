<template>
  <div class="step-screenshot">
    <div v-if="!expanded" class="screenshot-thumb" @click="expanded = true">
      <div class="thumb-placeholder">
        <span>📸</span>
        <p>点击查看截图</p>
      </div>
    </div>
    <div v-else class="screenshot-full">
      <div class="screenshot-wrapper" ref="wrapperRef">
        <img
            :src="screenshot"
            class="screenshot-image"
            @load="onImageLoad"
            @click="$emit('preview', screenshot)"
        />
        <canvas
            v-if="elementRect && imageLoaded"
            ref="canvasRef"
            class="highlight-canvas"
        ></canvas>
      </div>
      <button class="collapse-btn" @click="expanded = false">收起截图 ▲</button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue';

const props = defineProps({
  screenshot: { type: String, default: '' },
  elementRect: { type: Object, default: null }
});

defineEmits(['preview']);

const expanded = ref(false);
const imageLoaded = ref(false);
const canvasRef = ref(null);
const wrapperRef = ref(null);

function onImageLoad() {
  imageLoaded.value = true;
  nextTick(() => drawHighlight());
}

watch(() => [props.elementRect, expanded.value], () => {
  if (expanded.value && imageLoaded.value) {
    nextTick(() => drawHighlight());
  }
});

function drawHighlight() {
  if (!canvasRef.value || !wrapperRef.value || !props.elementRect) return;
  const img = wrapperRef.value.querySelector('img');
  if (!img || !img.naturalWidth) return;

  const canvas = canvasRef.value;
  const displayW = img.clientWidth;
  const displayH = img.clientHeight;
  canvas.width = displayW;
  canvas.height = displayH;

  const scaleX = displayW / img.naturalWidth;
  const scaleY = displayH / img.naturalHeight;

  const rect = props.elementRect;
  const ctx = canvas.getContext('2d');

  ctx.strokeStyle = '#f59e0b';
  ctx.lineWidth = 3;
  ctx.setLineDash([6, 3]);
  ctx.strokeRect(rect.x * scaleX, rect.y * scaleY, rect.w * scaleX, rect.h * scaleY);

  ctx.setLineDash([]);
  ctx.fillStyle = '#f59e0b';
  const label = `[${rect.index}]`;
  ctx.font = 'bold 14px sans-serif';
  const textW = ctx.measureText(label).width;
  const lx = rect.x * scaleX;
  const ly = rect.y * scaleY - 6;
  ctx.fillRect(lx, ly - 18, textW + 10, 20);
  ctx.fillStyle = '#fff';
  ctx.fillText(label, lx + 5, ly - 3);
}
</script>

<style scoped>
.step-screenshot {
  margin-top: 8px;
}
.screenshot-thumb {
  cursor: pointer;
  border: 2px dashed #e0e0e0;
  border-radius: 8px;
  padding: 16px;
  text-align: center;
  transition: border-color 0.2s;
}
.screenshot-thumb:hover {
  border-color: var(--accent, #667eea);
}
.thumb-placeholder span {
  font-size: 28px;
}
.thumb-placeholder p {
  margin: 6px 0 0;
  font-size: 12px;
  color: #64748b;
}
.screenshot-wrapper {
  position: relative;
  display: inline-block;
}
.screenshot-image {
  max-width: 100%;
  max-height: 240px;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  cursor: pointer;
  object-fit: contain;
  display: block;
}
.highlight-canvas {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
}
.collapse-btn {
  margin-top: 6px;
  padding: 4px 12px;
  font-size: 12px;
  background: #f0f0f0;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  color: #64748b;
  width: auto;
}
.collapse-btn:hover {
  background: #e0e0e0;
}
</style>
