from pathlib import Path
p = Path('tools/auto_tune_rules.py')
s = p.read_text(encoding='utf-8')
if 'def should_allow_rule_change(' not in s:
    guard = '''

def should_allow_rule_change(report: dict[str, Any], missed: dict[str, Any], notes: list[str]) -> bool:
    total = integer(report, "total_signals", 0)
    missed_count = integer(missed, "missed_pump_count", 0) if missed else 0
    generated = str(report.get("generated_at_utc", ""))[:10]
    if total < 20 and missed_count < 10:
        notes.append("rule_change_guard=blocked_low_sample_total_signals_lt20_and_missed_lt10")
        return False
    if generated:
        notes.append("rule_change_guard=daily_workflow_only; generated_date=" + generated)
    return True
'''
    s = s.replace('\ndef tune(rules: dict[str, Any], report: dict[str, Any], missed: dict[str, Any]) -> tuple[dict[str, Any], list[str], list[str]]:\n', guard + '\ndef tune(rules: dict[str, Any], report: dict[str, Any], missed: dict[str, Any]) -> tuple[dict[str, Any], list[str], list[str]]:\n', 1)
    s = s.replace('''    avg_ret, stop_rate, _t1_rate, _signals = tune_from_backtest(proposed, report, changes, notes)
    tune_from_missed(proposed, missed, avg_ret, stop_rate, changes, notes)
    if changes:
''', '''    if not should_allow_rule_change(report, missed, notes):
        return proposed, [], notes
    avg_ret, stop_rate, _t1_rate, _signals = tune_from_backtest(proposed, report, changes, notes)
    tune_from_missed(proposed, missed, avg_ret, stop_rate, changes, notes)
    if changes:
''', 1)
p.write_text(s, encoding='utf-8')
