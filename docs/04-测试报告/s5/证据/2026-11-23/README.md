# S5 证据目录说明（2026-11-23）

被测提交：`bd03fa1`（分支 `亓壬`）。执行人：S5。执行时间：08:52—09:04（东八区）。

## 最终证据

| 文件 | 内容 | 关联用例 |
|---|---|---|
| `s5-e2e-results.json` | 最终 30 条接口记录。含请求、期望、实际响应、traceId、时间与判定 | E2E-S5-001～008 |
| `s5-page-results.json` | 最终 6 条页面记录。含页面文本片段与按钮状态 | UI-S5-001～006 |
| `db-before.txt` | 测试前数据库快照。账户余额与交易、流水、渠道、风控计数 | 全部 |
| `db-after-final.txt` | 最终测试后快照。含成功单、失败单、收敛单的交易、流水与渠道记录 | E2E-S5-001、004、008 |
| `login-page.png` | 登录页截图，1440×900 | UI-S5-001～003 |
| `ui-after-login.png` | 登录成功后的账户页截图 | UI-S5-003 |
| `ui-deposit-result.png` | 存款结果面板截图，tradeNo=TRD20261123C1F6EC9C | UI-S5-004 |
| `ui-double-submit.png` | 连续点击提交后的页面截图 | UI-S5-006 |
| `login-page-dom.html` | 登录页渲染后的 DOM | UI-S5-001～003 |
| `修复回归/s5-page-results.json` | 存款页修复后的 6 条页面回归记录，确认弹窗数已收紧为判定条件 | UI-S5-001～006 |
| `修复回归/ui-*.png` | 回归时的登录、存款结果与连点截图 | UI-S5-003～006 |

## 过程记录

以下文件记录脚本修订过程。它们不是最终测试结果，只用于说明早期失败的原因。

| 文件 | 对应问题 |
|---|---|
| `s5-e2e-results-首轮.json` | 越权查询 URL 中文未编码；余额不足期望写错 |
| `s5-e2e-results-复跑.json` | 期望已改，越权 URL 仍未编码 |
| `s5-e2e-results-完整前.json` | 未配置内部令牌，渠道三态跳过 |
| `s5-e2e-results-渠道前.json` | 交易服务重启后环境变量缺失，多处 HTTP 500 |
| `s5-e2e-results-未加轮询.json` | 渠道三态首跑，UNKNOWN 未做轮询 |
| `s5-e2e-results-跨行前.json` | 加轮询后通过，尚未加入跨行转账 |
| `s5-page-results-首轮.json` | 误点“退出登录”按钮 |
| `s5-page-results-复跑.json` | 改用菜单跳转后仍误点“退出登录” |
| `s5-page-results-三轮.json` | 未选择存款账户 |
| `s5-page-results-四轮.json` | 未点二次确认弹窗 |

## 脱敏说明

接口证据不含令牌原文。登录响应里的 `data.token` 被替换成“<已脱敏>”。请求体里的 password 只记录“***”。内部令牌、数据库口令和 `X-Internal-Token` 头不写入任何证据文件。

数据库快照使用本机已有的 root 凭据查询。凭据来自 `logs/_*.cmd`，该文件被 `.gitignore` 排除，不在仓库里。
