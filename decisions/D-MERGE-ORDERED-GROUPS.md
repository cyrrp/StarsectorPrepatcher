> Project home:
> [StarsectorPrepatcher — overview, installation and downloads](https://github.com/kirpoly/StarsectorPrepatcher)

---

# D-MERGE-ORDERED-GROUPS — пересекающиеся поверхности объединяются в атомарные планы, а не упорядочиваются

**Статус:** ACTIVE
**Установлено:** решение владельца, 2026-09-12
**Governs:** `AGENTS.md` §Patch composition and transformation surfaces;
`docs/PATCHES.md` §Main agent; `docs/COMPATIBILITY.md` §Structural-владельцы

## Вопрос

Три пары патчей в dispatch нарушали первое правило композиции: их transformation surfaces
пересекаются, но согласованность достигалась порядком в switch-case и комментариями:

1. `StrategicModule`: `strategicJumpDestinationFirst` затем `strategicJumpDestinationIndex`.
   Index matcher потребляет и перепроверяет post-state destination-first — порядок являлся
   скрытой зависимостью.
2. `CampaignEngine`: `entityLookupIndexRepair` затем `campaignListenerThrottle`. Оба патчат
   `readdChangeListeners()V`-границу в `advance(FLcom/fs/starfarer/util/A/new;)V`: repair
   якорится после границы в любой форме, throttle заменяет саму границу.
3. Класс `A`: `intelCallbackCache` затем `mapHitTest`. Комментарий «keep the wrapper last»
   кодифицировал требование, что call-site правки попадают в сохранённое оригинальное тело,
   а не в dispatch-wrapper — через позицию в switch, а не через контракт.

Порядок-позиция не доказуем: reorder dispatch молча меняет результат, а отказ позднего члена
оставляет ранний применённым — полугрупповое состояние доходило до сериализации.

## Решение

Каждая пара становится одним атомарным планом в форме `MarketAdvancePlan`: единый
`StructuredPatch` с одним matcher, одним mutation plan и одной combined postcondition.

Общая механика всех трёх планов одинакова:

- Оба члена проверяются на одинаковых входных bytes; isolated `ClassNode`-candidate строится в
  явном порядке, записанном в коде; поздний член потребляет фактическое состояние candidate
  после раннего, а не маркер и не vanilla bytes.
- Один ownership marker `smo$patched$<plan>` и одно private static final mask-поле коммитятся
  одной транзакцией. `ALREADY_APPLIED` требует marker, совпадающий mask и полную combined
  postcondition.
- Несовместимость любого запрошенного члена отклоняет весь plan: класс остаётся полностью
  vanilla со `SKIPPED_STRUCTURAL`. Частичного применения не существует.
- Legacy split markers и unowned hook-shaped state отвергаются, а не усваиваются: постороннее
  состояние не принимается под unified ownership.

По группам:

- **`strategicJumpDestinationPlan`** владеет обеими поверхностями
  `findNearestSafeJumpPoint` и expired-plan ветвью `updateJumpPlanTo`. Порядок внутри плана:
  destination-first → index. Mask `{1}` (first only) и `{3}` (оба) достижимы; `{2}`
  недостижим — `PrepatcherConfig` нормализует index-only в disabled с warning, потому что
  index-компонент определён над post-state destination-first.
- **`campaignListenerPlan`** владеет listener-restoration boundary `CampaignEngine.advance`.
  Порядок внутри плана: repair → throttle. Mask свободный `{1},{2},{3}` — поведения независимы,
  и конфигурации с одним членом легальны.
- **`mapInteractionPlan`** владеет class-A `o00000` Intel callback site и `OO0000(FFF)`
  hit test. Порядок внутри плана: callback → hit-test. Mask свободный `{1},{2},{3}`.
  Здесь честная формулировка пересечения слабее: методы разные, пересекается лишь
  method inventory класса — hit-test переименовывает `OO0000` и добавляет wrapper, поэтому
  call-site член обязан видеть сохранённое оригинальное тело. План делает эту оговорку
  структурной: reservation полного hit-test метода доказуется matcher'ом, а не комментарием.

## Почему не альтернатива

**Один сплавленный патч на группу.** Отвергнут: для `campaignListenerPlan` и
`mapInteractionPlan` поведения семантически независимы — сплав был бы if/else по двум
доказанным контрактам без выигрыша, а пара сохраняет оба member matcher/postcondition и оба
kill-switch. Для `strategicJumpDestinationPlan` сплав бессмыслен: index без first
существовать не может, а first без index — поддерживаемая конфигурация.

**Один публичный ключ на группу.** Отвергнуто по правилу «одно поведение — один ключ»: для
`campaignListenerPlan` и `mapInteractionPlan` оба поведения независимо полезны и независимо
отключаемы. Ключи остаются selectors feature mask; атомарность при единственном включённом
члене обеспечивается тем же механизмом — один marker, один mask, одна транзакция.
`strategicJumpDestinationPlan` сохраняет оба ключа, но `{2}` недостижим через config
normalization.

**Сохранить ordering + комментарии.** Отвергнуто: это и есть нарушение правила композиции,
которое изменение устраняет.

## Следствия

1. Status lines меняются намеренно: два id становятся одной строкой группы; refusal reasons
   становятся причинами combined contract; `ALREADY_APPLIED` требует marker + mask + полную
   postcondition. Каждая разница задокументирована в отчёте по миграции.
2. Rollback semantics меняются: malformed input, раньше оставлявший первый член применённым,
   теперь даёт полностью неизменённый класс. Это и есть цель изменения.
3. Граница «ordered vs grouped» остаётся и теперь звучит так: атомарен план по пересекающимся
   поверхностям; порядок dispatch + generic composition revalidation разрешены только между
   id, чьи поверхности genuinely disjoint.
4. Отдельные id, сохранённые из-за genuinely disjoint поверхностей, — это claim, а не
   конвенция. Каждый claim подкреплён тестом «ни один не может быть молча пропущен из-за
   другого»:
   - `strategicJumpDestinationIndex` на `JumpPoint`, `JumpPointAPI$JumpDestination`,
     `BaseLocation` — disjoint от `StrategicModule` plan (другие классы, не читают
     `findNearestSafeJumpPoint`/`updateJumpPlanTo`); тест в
     `StrategicJumpDestinationIndexTransformerTest` применяет `JumpPoint` patch при
     отклонённом plan.
   - `coreWorldsExtentCache`, `campaignCacheLifecycle`, `marketScheduler` на `CampaignEngine`
     — disjoint от listener-restoration boundary; тест в `StructuralCompatibilityTest`
     применяет их на классе с отклонённым `campaignListenerPlan`.
   - `intelCallbackCache` на `EventsPanel` — disjoint от class-A `mapInteractionPlan`; тест
     применяет его при отклонённом plan класса `A`.
5. `mapInteractionPlan` — единственная группа, где member surfaces не пересекаются по
   инструкциям (разные методы); группировка отвечает на разделяемый method inventory и
   требование формы. Это зафиксировано, чтобы будущий читатель не искал мнимое
   instruction-level overlap.

## Что этим решением не разрешено

- Порядок между планом и другими независимыми патчами того же класса по-прежнему
  dispatch order + generic composition revalidation — это разрешено, потому что их
  поверхности disjoint. Решение не вводит глобальный scheduler для всех патчей класса.
- Runtime-семантика членов не пересмотрена: план меняет механизм применения, а не то, что
  член делают после commit. Отдельное решение потребуется, если поведение члена меняется.
- `strategicJumpDestinationPlan` не решает, может ли index-компонент когда-либо
  существовать без first; текущая config normalization — зафиксированное поведение, а не
  ответ навсегда.

## Проверка

`verify-structural` exit 0 с 91 OK-строками; изменённые status lines перечислены и
прокомментированы в отчёте. По группам: применение каждого mask, idempotent reprocess,
atomic rollback при сломанном члене, legacy-marker и unowned-state rejection, и для каждой
сохранённой disjoint пары — тест, что один не молча пропускает другой. См.
`StructuralCompatibilityTest`, `StrategicJumpDestinationPlanTransformerTest`,
`StrategicJumpDestinationIndexTransformerTest`,
`StrategicJumpDestinationIndexRuntimeTest`.
