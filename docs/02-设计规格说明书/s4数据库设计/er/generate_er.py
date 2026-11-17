"""Generate the experiment-two ER source directly from its SQL baseline."""
from pathlib import Path
import re

HERE = Path(__file__).resolve().parent
sql = (HERE.parent / 'sql' / 'schema.sql').read_text(encoding='utf-8')
tables = {}
for match in re.finditer(r"CREATE TABLE `?(\w+)`? \((.*?)\n\) ENGINE=.*?COMMENT='([^']+)';", sql, re.S):
    name, body, title = match.groups()
    columns = []
    for line in body.splitlines():
        col = re.match(r'\s+`?(\w+)`?\s+((?:BIGINT|INT|TINYINT|VARCHAR|CHAR|DECIMAL|DATETIME)(?:\([\d,]+\))?(?: UNSIGNED)?)\s+(.*)', line)
        if col:
            field, dtype, rest = col.groups()
            columns.append((field, dtype, 'NOT NULL' not in rest and field != 'id'))
    unique = re.findall(r'UNIQUE KEY \w+ \(([^)]+)\)', body)
    foreign = re.findall(r'FOREIGN KEY \((\w+)\) REFERENCES `?(\w+)`? \((\w+)\)', body)
    tables[name] = dict(title=title, columns=columns, unique=[u.split(', ') for u in unique], foreign=foreign)

assert len(tables) == 10
assert sum(len(t['foreign']) for t in tables.values()) == 11

lines = ['@startuml',
    "' Generated from experiment-two sql/schema.sql. Re-run this script after SQL changes.",
    'hide circle', 'skinparam linetype polyline',
    'skinparam backgroundColor #FFFFFF', 'skinparam shadowing false',
    'skinparam roundcorner 8', 'skinparam defaultFontName Microsoft YaHei',
    'skinparam defaultFontSize 13', 'skinparam nodesep 85', 'skinparam ranksep 115',
    'skinparam padding 8', 'skinparam ArrowColor #2563EB',
    'skinparam ArrowFontColor #111827', 'skinparam ArrowFontSize 13',
    'skinparam classBackgroundColor #FFFFFF', 'skinparam classBorderColor #9CA3AF',
    'skinparam classFontColor #111827', 'skinparam classHeaderBackgroundColor #EFF6FF',
    'title\n数字银行系统 · 实验二数据库完整 ER 图\n<size:14>payment_db  /  10 张表 · 11 个物理外键 · 全字段</size>\nend title',
    '']

for name, table in tables.items():
    lines.append(f'entity "{table["title"]}\\n{name}" as {name} {{')
    foreign_fields = {f[0] for f in table['foreign']}
    for field, dtype, nullable in table['columns']:
        flags = []
        if field == 'id':
            flags.append('PK')
        if field in foreign_fields:
            flags.append('FK')
        for i, group in enumerate(table['unique'], 1):
            if field in group:
                flags.append(f'U{i}')
        tag = f'  <<{", ".join(flags)}>>' if flags else ''
        lines.append(f'  {{field}} {"" if nullable else "* "}{field} : {dtype}{tag}')
    lines.append('}')
    lines.append('')

edges = []
labels = {
    ('credential', 'customer_id'): '拥有凭证',
    ('account', 'customer_id'): '持有账户',
    ('account_status_log', 'account_id'): '状态历史',
    ('transaction', 'account_id'): '主账户交易',
    ('transaction_flow', 'trade_no'): '产生流水',
    ('transaction_flow', 'account_id'): '账户流水',
    ('transfer_order', 'trade_no'): '转账明细',
    ('transfer_order', 'payer_account_id'): '付款方',
    ('transfer_order', 'payee_account_id'): '收款方（本行）',
    ('risk_event', 'trade_no'): '风险记录',
    ('risk_event', 'rule_id'): '规则命中',
}
for child, table in tables.items():
    for field, parent, ref in table['foreign']:
        nullable = next(c[2] for c in table['columns'] if c[0] == field)
        unique = [field] in table['unique']
        # Parent endpoint is optional only for nullable FK; child endpoint can be zero.
        parent_end = '|o' if nullable else '||'
        child_end = 'o|' if unique else 'o{'
        direction = {
            ('transaction_flow', 'trade_no'): 'left',
            ('transfer_order', 'trade_no'): 'right',
            ('transaction', 'account_id'): 'down',
            ('risk_event', 'trade_no'): 'down',
        }.get((child, field), '')
        connector = f'-{direction}-' if direction else '--'
        lines.append(f'{parent} {parent_end}{connector}{child_end} {child} : {labels[(child, field)]}')
        edges.append((parent, ref, child, field))

lines += [
    'risk_rule -[hidden]right- audit_log',
    'legend bottom left',
    '  PK = 主键；FK = 物理外键；U1/U2/U3 = 本表唯一约束（同号字段组成联合唯一键）。',
    '  字段前 ● 表示 NOT NULL；无 ● 表示可空。 || = 恰好 1；o| = 0 或 1；o{ = 0 到多。',
    '  关联引用业务唯一键（customer_id / account_id / trade_no / rule_id），不引用自增 id。',
    '  transaction → transfer_order 为 1 : 0..1：非转账交易没有转账单。',
    '  payee_account_id 可空：本行转账必须关联账户；跨行转账使用外部收款信息。',
    '  identity_id / operator_identity_id 属应用层身份引用，不创建 customer 外键。',
    '  audit_log 独立留存；trade_no / target_ref 为逻辑引用，不创建物理外键。',
    '  可用余额 = book_balance − reserved_amount，为派生值，不单独落库。',
    'endlegend', '@enduml', '']

(HERE / '完整ER图.puml').write_text('\n'.join(lines), encoding='utf-8')
print(f'Generated: 10 tables, {sum(len(t["columns"]) for t in tables.values())} columns, {len(edges)} foreign keys.')
