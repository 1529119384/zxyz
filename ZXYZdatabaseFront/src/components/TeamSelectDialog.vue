<template>
  <el-dialog
    :model-value="visible"
    title="选择默认团队"
    width="420px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    @update:model-value="emitVisibleChange"
  >
    <el-radio-group
      :model-value="selectedTeamId"
      class="team-select-list"
      @update:model-value="emitSelectedTeamChange"
    >
      <el-radio v-for="team in teams" :key="team.id" :value="team.id">
        {{ team.name }}
      </el-radio>
    </el-radio-group>
    <el-checkbox :model-value="setAsDefault" @update:model-value="emitDefaultChange"
      >设为默认团队</el-checkbox
    >
    <template #footer>
      <el-button :disabled="submitting" @click="emit('skip')">跳过</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!selectedTeamId || submitting"
        @click="emit('confirm')"
      >
        确定
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  teams: {
    type: Array,
    default: () => [],
  },
  selectedTeamId: {
    type: [Number, String],
    default: null,
  },
  setAsDefault: {
    type: Boolean,
    default: true,
  },
  submitting: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits([
  'update:visible',
  'update:selectedTeamId',
  'update:setAsDefault',
  'confirm',
  'skip',
])

function emitVisibleChange(visible) {
  if (!visible && props.submitting) {
    return
  }

  emit('update:visible', visible)
}

function emitSelectedTeamChange(teamId) {
  emit('update:selectedTeamId', teamId)
}

function emitDefaultChange(value) {
  emit('update:setAsDefault', value)
}
</script>

<style scoped>
.team-select-list {
  display: grid;
  gap: 10px;
  margin-bottom: 16px;
}
</style>
