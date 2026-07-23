<template>
  <div ref="containerRef" class="virtual-message-list">
    <DynamicSizeList
      v-if="containerHeight > 0"
      ref="listRef"
      :data="messages"
      :total="messages.length"
      :item-size="estimateItemSize"
      :estimated-item-size="ESTIMATED_MESSAGE_ITEM_SIZE"
      :cache="4"
      :height="containerHeight"
      width="100%"
      :use-is-scrolling="false"
      @scroll="handleScroll"
    >
      <template #default="{ data, index, style }">
        <div
          :style="{
            ...style,
            boxSizing: 'border-box',
            paddingLeft: '18px',
            paddingRight: '18px',
            paddingBottom: '14px',
          }"
          @contextmenu.prevent="emit('contextmenu', $event, data[index])"
        >
          <MessageBubble
            :message="data[index]"
            :current-user-id="currentUserId"
            :mention-name="mentionName"
            :can-review-project-create-requests="canReviewProjectCreateRequests"
            :reviewing-application-id="reviewingApplicationId"
            @recall="emit('recall', $event)"
            @file-card-action="emit('file-card-action', $event)"
            @review-project-create-request="emit('review-project-create-request', $event)"
          />
        </div>
      </template>
    </DynamicSizeList>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { DynamicSizeList } from 'element-plus/es/components/virtual-list/index.mjs'

import MessageBubble from './MessageBubble.vue'

const props = defineProps({
  messages: { type: Array, required: true },
  currentUserId: { type: [Number, String], default: null },
  mentionName: { type: Function, required: true },
  canReviewProjectCreateRequests: { type: Boolean, default: false },
  reviewingApplicationId: { type: [Number, String], default: null },
})

const emit = defineEmits([
  'load-older',
  'contextmenu',
  'recall',
  'file-card-action',
  'review-project-create-request',
])

const ESTIMATED_MESSAGE_ITEM_SIZE = 80

const listRef = ref(null)
const containerRef = ref(null)
const containerHeight = ref(0)
const isNearBottomRef = ref(true)
const TOP_THRESHOLD = 200
const BOTTOM_THRESHOLD = 150

let resizeObserver = null

onMounted(() => {
  if (!containerRef.value) return
  containerHeight.value = containerRef.value.clientHeight
  resizeObserver = new ResizeObserver((entries) => {
    for (const entry of entries) {
      containerHeight.value = entry.contentRect.height
    }
  })
  resizeObserver.observe(containerRef.value)
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})

function estimateItemSize(index) {
  const msg = props.messages[index]
  if (!msg) return ESTIMATED_MESSAGE_ITEM_SIZE
  switch (msg.messageType) {
    case 'SYSTEM_NOTIFICATION':
    case 'ANNOUNCEMENT':
      return 100
    case 'FILE_CARD':
      return 120
    case 'PROJECT_CREATION_APPLICATION':
      return 140
    default: {
      const len = (msg.content || '').length
      return len > 80 ? 90 : 65
    }
  }
}

function handleScroll(_scrollDir, scrollOffset) {
  const list = listRef.value
  if (!list) return

  if (scrollOffset < TOP_THRESHOLD) {
    const first = props.messages[0]
    if (first) {
      emit('load-older', first.messageId || first.clientMessageId)
    }
  }

  const windowEl = list.windowRef
  if (windowEl) {
    const distanceFromBottom = windowEl.scrollHeight - windowEl.scrollTop - windowEl.clientHeight
    isNearBottomRef.value = distanceFromBottom < BOTTOM_THRESHOLD
  }
}

function scrollToBottom() {
  const lastIndex = props.messages.length - 1
  if (lastIndex < 0 || !listRef.value) return
  listRef.value.scrollToItem(lastIndex, 'end')
  isNearBottomRef.value = true
}

function scrollToItem(index, alignment) {
  if (listRef.value) {
    listRef.value.scrollToItem(index, alignment)
  }
}

defineExpose({
  scrollToBottom,
  scrollToItem,
  isNearBottomRef,
})
</script>

<style scoped>
.virtual-message-list {
  height: 100%;
}
</style>
