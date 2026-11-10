// 交易受理提交 composable：统一处理 S5 验收三件事——
// 1. 提交 loading / 结果提示（错误提示直接用后端 message）
// 2. 网络失败后的重试复用原 requestId（幂等，不重复入账）
// 3. 非终态（PROCESSING/PENDING/RECOVERING）交易按 tradeNo 查询收敛
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { isFinalStatus } from '../utils/status'

/**
 * @param {Function} submitFn 无参提交函数（闭包携带表单数据 + 原 requestId）
 * @param {Function} queryFn  (tradeNo) => Promise<统一响应体>，查询交易当前状态
 */
export function useTradeSubmit(submitFn, queryFn) {
  const submitting = ref(false)
  const networkFailed = ref(false) // true = 网络失败，等待重试（复用原 requestId）
  const result = ref(null) // 最近一次后端统一响应体 { code, message, data, traceId }
  const polling = ref(false)

  let currentSubmit = null
  let pollTimer = null
  let pollCount = 0

  function clearPoll() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
    polling.value = false
    pollCount = 0
  }

  function notify(res) {
    if (res.code === 'SUCCEEDED') {
      ElMessage.success(res.message || '操作成功')
    } else if (['PROCESSING', 'PENDING', 'RECOVERING', 'RECEIVED'].includes(res.code)) {
      ElMessage.warning(res.message || '交易处理中，请稍后查询')
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  }

  /** 非终态交易：每 5 秒自动查询一次，最多 6 次；查到终态即停止 */
  function startAutoPoll() {
    clearPoll()
    polling.value = true
    pollTimer = setInterval(async () => {
      pollCount += 1
      const tradeNo = result.value?.data?.tradeNo
      if (!tradeNo || pollCount > 6) {
        clearPoll()
        return
      }
      try {
        const res = await queryFn(tradeNo)
        if (res.code === 'SUCCEEDED' || res.data) {
          result.value = res
          if (isFinalStatus(res.data?.status)) {
            clearPoll()
            notify(res)
          }
        }
      } catch {
        // 单次查询失败不打断轮询
      }
    }, 5000)
  }

  /** 提交（或网络失败后的重试）。重试复用调用方闭包里的原 requestId。 */
  async function submit() {
    currentSubmit = submitFn
    submitting.value = true
    networkFailed.value = false
    try {
      const res = await currentSubmit()
      result.value = res
      notify(res)
      const status = res.data?.status
      if (status && !isFinalStatus(status)) startAutoPoll()
      return res
    } catch (e) {
      // 网络层错误（请求未确认送达）：保留 requestId 供重试
      networkFailed.value = true
      ElMessage.error(e.message || '网络异常，请重试')
      return null
    } finally {
      submitting.value = false
    }
  }

  /** 网络失败后的重试：复用同一 submit 闭包（同一 requestId） */
  async function retry() {
    return submit()
  }

  /** 手动查询最新状态（待确认交易收敛入口） */
  async function refreshStatus() {
    const tradeNo = result.value?.data?.tradeNo
    if (!tradeNo) return
    try {
      const res = await queryFn(tradeNo)
      if (res.code === 'SUCCEEDED' || res.data) {
        result.value = res
        notify(res)
        const status = res.data?.status
        if (status && !isFinalStatus(status)) startAutoPoll()
        else clearPoll()
      } else {
        ElMessage.error(res.message || '查询失败')
      }
    } catch (e) {
      ElMessage.error(e.message || '网络异常')
    }
  }

  function reset() {
    clearPoll()
    submitting.value = false
    networkFailed.value = false
    result.value = null
    currentSubmit = null
  }

  return { submitting, networkFailed, result, polling, submit, retry, refreshStatus, reset }
}
