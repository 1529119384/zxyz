// @ts-check
// 分页常量：全仓前端页长的唯一来源。
//
// 为什么要有这个文件：页长此前散落在四处互不相干的字面量里 —— useMyShareList 写 10、
// useSpaceFileList 写 50、useFileSearch 写 20、api/files.ts 的默认参数又写 20 ——
// 改一处不会影响另一处，也没人知道「哪个数字才是对的」。
// 后端口径见 ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/PageResult.java。

/** 后端 PageResult.DEFAULT_PAGE_SIZE 的镜像：请求不带 pageSize 时后端用它。 */
export const DEFAULT_PAGE_SIZE = 20

/**
 * 后端 PageResult.MAX_PAGE_SIZE 的镜像。
 *
 * 07-P2-4 之前只有 file-service / email-service 之外的接口走这个上限，file-service 自己
 * 硬编码 100、搜索硬编码 50、邮件记录硬编码 100 ⇒ 用户在上面的选项里选 200 时，
 * 后端只按 100 或 50 分页、前端却按 200 算总页数，**每翻一页跳掉一批数据**。
 * 现在三处都改走 {@code PageResult.normalizePageSize}，上限与这里的 200 一致。
 *
 * 第二道防线是「按响应回传的 pageSize 校准」（usePagedList / useSpaceFileList /
 * useFileSearch / EmailRecordPanel 都做了），后端万一再漂也能自愈。
 */
export const MAX_PAGE_SIZE = 200

/** 我的分享 / 回收站这类小列表的 el-pagination 页长选项。 */
export const PAGE_SIZE_OPTIONS = [10, 20, 50]

/** 空间文件列表的 el-pagination 页长选项（目录页一屏尽量铺满）。 */
export const SPACE_PAGE_SIZE_OPTIONS = [20, 50, 100, 200]

/**
 * 各列表上下文的默认页长。
 *
 * 这些值是**既有行为的固化**，不是新约定：改动它们会直接改变用户看到的每页条数。
 * 单独列出来的意义是让差异一眼可见（此前它们藏在四个文件的字面量里，谁也看不出不一致）。
 *
 * @type {Readonly<Record<string, number | undefined>>}
 */
export const PAGE_SIZE_BY_CONTEXT = Object.freeze({
  /** 我的分享：后端 ShareManager.DEFAULT_PAGE_SIZE 同样是 10，两边必须一致。 */
  myShare: 10,
  /** 回收站：与后端 PageResult.DEFAULT_PAGE_SIZE 对齐。 */
  recycleBin: DEFAULT_PAGE_SIZE,
  /** 空间文件列表：目录页一屏尽量铺满。 */
  spaceFiles: 50,
  /** 文件搜索：与后端默认值对齐。 */
  fileSearch: DEFAULT_PAGE_SIZE,
  /** 历史邮件记录：前端原本写死 10（后端默认 20，但前端总是显式传页长）。 */
  emailRecord: 10,
})

/**
 * 按上下文取默认页长；未登记的上下文回落到后端默认值。
 *
 * @param {string} [context] - 上下文键，见 PAGE_SIZE_BY_CONTEXT。
 * @returns {number} 页长。
 */
export function resolvePageSize(context) {
  return (context ? PAGE_SIZE_BY_CONTEXT[context] : undefined) ?? DEFAULT_PAGE_SIZE
}

/**
 * 把外部传入的页长（下拉框给的 number、URL 给的 string、undefined）归一化成合法页长。
 *
 * 与后端 PageResult.normalizePageSize 同口径（`/` 与 `Math.floor` 的差别只在浮点场景，
 * 前端的页长来源是固定选项或整数输入）。唯一有意的差别：「未指定」回落
 * DEFAULT_PAGE_SIZE 而不是各上下文自己的值 —— 上下文默认值由 resolvePageSize 负责。
 *
 * @param {unknown} value - 待归一化的值。
 * @returns {number} 归一化后的页长，范围 [1, MAX_PAGE_SIZE] 或 DEFAULT_PAGE_SIZE。
 */
export function normalizePageSize(value) {
  const size = Number(value)

  if (!Number.isFinite(size) || size < 1) {
    return DEFAULT_PAGE_SIZE
  }

  return Math.min(Math.floor(size), MAX_PAGE_SIZE)
}
