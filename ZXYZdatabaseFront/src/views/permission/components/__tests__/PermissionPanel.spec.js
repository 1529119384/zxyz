/**
 * `PermissionPanel` 测试。
 *
 * 它是本次从 `permission/index.vue` 抽出的四个共用块之一（面板壳），
 * 被权限字典表与审计表复用。要钉住的是**渲染与否的分支**：
 * `subtitle` / `tip` 为空串时对应的 `<p>` 必须**不渲染**（而不是渲染成空标签）——
 * 原实现是 `v-if`，抽组件时最容易退化成「永远渲染」。
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'

import PermissionPanel from '@/views/permission/components/PermissionPanel.vue'

function mountPanel(props = {}, slots = {}) {
  return mount(PermissionPanel, {
    props: { title: '系统角色任命', ...props },
    slots,
  })
}

describe('PermissionPanel', () => {
  it('总是渲染标题', () => {
    const wrapper = mountPanel()
    expect(wrapper.find('h3').text()).toBe('系统角色任命')
  })

  it('subtitle 为空时整个 <p> 不渲染', () => {
    expect(mountPanel().find('.panel-title p').exists()).toBe(false)
    expect(mountPanel({ subtitle: '说明' }).find('.panel-title p').text()).toBe('说明')
  })

  it('tip 为空时提示不渲染；非空时渲染为 .permission-tip', () => {
    expect(mountPanel().find('.permission-tip').exists()).toBe(false)
    expect(mountPanel({ tip: '缺少权限' }).find('.permission-tip').text()).toBe('缺少权限')
  })

  it('variant 映射为修饰类；default 不带修饰类', () => {
    expect(mountPanel().classes()).toEqual(['panel'])
    expect(mountPanel({ variant: 'assignment' }).classes()).toEqual([
      'panel',
      'panel--assignment',
    ])
    expect(mountPanel({ variant: 'wide' }).classes()).toEqual(['panel', 'panel--wide'])
  })

  it('默认插槽内容渲染在面板内部', () => {
    const wrapper = mountPanel({}, { default: '<div class="inner">插槽内容</div>' })
    expect(wrapper.find('.panel .inner').exists()).toBe(true)
  })
})
