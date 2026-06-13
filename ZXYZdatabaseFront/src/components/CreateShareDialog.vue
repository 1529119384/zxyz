<template>
  <el-dialog
    :model-value="visible"
    title="分享文件"
    width="520"
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    @update:model-value="handleVisibleChange"
  >
    <div class="dialog-content">
      <div class="share-target">
        <span class="share-target__label">分享内容</span>
        <span class="share-target__value">{{ targetTitle || '-' }}</span>
      </div>

      <el-form label-width="96px" class="share-form">
        <el-form-item label="有效期">
          <el-radio-group v-model="form.expireType">
            <el-radio
              v-for="option in SHARE_EXPIRE_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="提取码">
          <div class="password-section">
            <el-switch v-model="form.needPassword" />
            <template v-if="form.needPassword">
              <el-input
                v-model="form.password"
                class="password-input"
                maxlength="4"
                placeholder="请输入4位提取码"
                @input="handlePasswordInput"
              />
              <el-button link type="primary" @click="resetPassword"> 重新生成 </el-button>
            </template>
          </div>
          <div v-if="form.needPassword" class="form-tip">仅允许输入 4 位大小写字母或数字</div>
        </el-form-item>

        <el-form-item label="自动填充">
          <el-checkbox v-model="form.autoFillPassword" :disabled="!form.needPassword">
            链接自动带上提取码
          </el-checkbox>
        </el-form-item>

        <el-form-item label="访问人数">
          <div class="access-section">
            <el-radio-group v-model="accessMode">
              <el-radio value="unlimited"> 不限制 </el-radio>
              <el-radio value="limited"> 限制人数 </el-radio>
            </el-radio-group>
            <el-input-number
              v-model="form.maxAccessCount"
              :min="1"
              :max="99"
              :disabled="accessMode !== 'limited'"
            />
          </div>
          <div class="form-tip">这里限制的是成功通过分享校验的浏览器数量</div>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button :disabled="submitting" @click="close"> 取消 </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="submitting"
          @click="handleSubmit"
        >
          创建分享
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import {
  generateSharePassword,
  getShareTargetTitle,
  isValidSharePassword,
  sanitizeSharePassword,
  SHARE_EXPIRE_OPTIONS,
} from '@/models/share'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  items: {
    type: Array,
    default: () => [],
  },
  submitting: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['submit', 'update:visible'])

const form = reactive({
  expireType: '7d',
  needPassword: true,
  password: generateSharePassword(),
  autoFillPassword: true,
  maxAccessCount: 10,
})
const accessMode = ref('limited')

const targetTitle = computed(() => getShareTargetTitle(props.items))

function resetForm() {
  form.expireType = '7d'
  form.needPassword = true
  form.password = generateSharePassword()
  form.autoFillPassword = true
  form.maxAccessCount = 10
  accessMode.value = 'limited'
}

function handlePasswordInput(value) {
  form.password = sanitizeSharePassword(value)
}

function resetPassword() {
  form.password = generateSharePassword()
}

function close() {
  if (props.submitting) {
    return
  }

  emit('update:visible', false)
}

function handleVisibleChange(value) {
  if (!value) {
    close()
    return
  }

  emit('update:visible', true)
}

function handleSubmit() {
  if (props.submitting) {
    return
  }

  if (!props.items.length) {
    ElMessage.warning('请选择要分享的文件或文件夹')
    return
  }

  if (form.needPassword && !isValidSharePassword(form.password)) {
    ElMessage.warning('提取码必须是 4 位大小写字母或数字')
    return
  }

  emit('submit', {
    expireType: form.expireType,
    needPassword: form.needPassword,
    password: form.needPassword ? form.password : '',
    autoFillPassword: form.needPassword ? form.autoFillPassword : false,
    maxAccessCount: accessMode.value === 'limited' ? Number(form.maxAccessCount) : 0,
  })
}

watch(
  () => props.visible,
  (visible, previousVisible) => {
    if (visible && !previousVisible) {
      resetForm()
    }
  },
)

watch(
  () => form.needPassword,
  (needPassword) => {
    if (!needPassword) {
      form.autoFillPassword = false
      return
    }

    if (!isValidSharePassword(form.password)) {
      form.password = generateSharePassword()
    }
    form.autoFillPassword = true
  },
)

watch(accessMode, (mode) => {
  if (mode === 'limited' && (!form.maxAccessCount || form.maxAccessCount < 1)) {
    form.maxAccessCount = 10
  }
})
</script>

<style scoped>
.dialog-content {
  padding-top: 8px;
}

.share-target {
  margin-bottom: 20px;
  padding: 12px 14px;
  border-radius: 8px;
  background: #f5f7fa;
}

.share-target__label {
  color: #909399;
  margin-right: 12px;
}

.share-target__value {
  color: #303133;
  word-break: break-all;
}

.password-section,
.access-section {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.password-input {
  width: 160px;
}

.form-tip {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
  line-height: 18px;
}
</style>
