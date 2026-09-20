// 项目状态码常量。取值与后端 project.status 一致（Flyway 列注释：0-正常 / 1-归档 / 2-禁用）。
// 本文件是项目状态码的唯一权威来源。
export const PROJECT_STATUS = Object.freeze({
  NORMAL: 0, // 正常
  ARCHIVED: 1, // 已归档
  DISABLED: 2, // 已禁用
})
