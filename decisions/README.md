> Project home:
> [StarsectorPrepatcher — overview, installation and downloads](https://github.com/kirpoly/StarsectorPrepatcher)

---

# Решения

По файлу на разрешённый дизайн-вопрос: `D-<SLUG>.md`. Файл фиксирует не текущее правило —
им владеет `docs/PATCHES.md`, `docs/COMPATIBILITY.md` или `docs/VALIDATION.md` — а выбор:
какие варианты рассматривались, почему принят этот, что он закрывает и что оставляет открытым.

Решение заводится, когда изменение выбирает между защитимыми альтернативами, принимает
отклонение от vanilla-семантики или принимает остаточный риск. Чисто техническая реализация
согласованного подхода решения не требует.

| Файл | Вопрос | Статус |
| --- | --- | --- |
| [D-FAIL-OPEN.md](D-FAIL-OPEN.md) | Что происходит, когда патч не может примениться | ACTIVE |
| [D-OWNED-FORK-FIRST-CLASS.md](D-OWNED-FORK-FIRST-CLASS.md) | Как относиться к собственному форку AoTD | ACTIVE |
| [D-STRUCTURAL-DISCOVERY.md](D-STRUCTURAL-DISCOVERY.md) | Как находить цели без обфусцированных имён | RESOLVED, не реализовано |
| [D-TEMPMOD-AGGRESSIVE-SEMANTICS.md](D-TEMPMOD-AGGRESSIVE-SEMANTICS.md) | Допустимое расхождение с vanilla в expiry scheduler | ACTIVE |
| [D-PATCH-SHAPE.md](D-PATCH-SHAPE.md) | Форма патча: доказательство контракта до мутации | ACTIVE |

## Шаблон

```markdown
# D-<SLUG> — <что решается одной строкой>

**Статус:** ACTIVE | RESOLVED, не реализовано | SUPERSEDED [[D-...]]
**Установлено:** <релиз или «решение владельца, YYYY-MM-DD»>
**Governs:** <документ §секция>, <документ §секция>

## Вопрос
## Решение
## Почему не альтернатива
## Следствия
## Что этим решением не разрешено
## Проверка
```

Секция `Governs` — рабочий инструмент: меняя названную в ней секцию, решение нужно перечитать
и обновить либо перевести в `SUPERSEDED`, в том же изменении.
