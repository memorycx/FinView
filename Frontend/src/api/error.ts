/**
 * 接口错误：统一由 request 抛出，业务层通过 code 判断错误类型
 */
export class ApiError extends Error {
  /**
   * 错误码
   * - 400 参数错误
   * - 404 资源不存在
   * - 500 服务端内部错误
   * - 其他：透传后端业务错误码
   */
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}
