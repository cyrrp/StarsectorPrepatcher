> Project home:
> [StarsectorPrepatcher — overview, installation and downloads](https://github.com/kirpoly/StarsectorPrepatcher)

---

# D-PRESENTATION-ENVELOPE — presentation-трансформер остаётся отдельной оболочкой

**Статус:** ACTIVE
**Установлено:** решение владельца, 2026-09-12
**Governs:** `docs/PATCHES.md` §Fast-forward presentation coalescing;
`docs/COMPATIBILITY.md` §Presentation и structural patches, §Жизненный цикл патча,
§Classloader-архитектура и Faster Rendering, §Статусы

## Вопрос

После перевода всех патчей `PrepatcherTransformer` на форму [[D-PATCH-SHAPE]] вторым
механизмом трансформации остаётся `FastForwardPresentationTransformer`: шестнадцать ключей
`patch.fastForward*` обслуживаются его декларативной таблицей `PLANS`. Вопрос: свернуть ли
эти компоненты в тот же per-patch envelope — `StructuredPatch` под оболочкой `apply(...)`
— или оставить отдельную оболочку.

## Решение

Оставить отдельной оболочкой. Конечное состояние: **одна форма патча, две оболочки.**

Форма патча в presentation-трансформере уже соблюдена. `inspect(plan, node, mask)` —
read-only доказательство: каждый call site классифицируется как original/wrapper, receiver и
аргументы проверяются через `Analyzer<SourceValue>`, ownership fields, feature mask и полный
hook inventory сверяются с ожидаемыми; любой отказ — `StructuralMismatch` до первой мутации.
`apply` изменяет только доказанные `SiteRef` instruction indexes и только в клоне
`cloneNode(input)` — входной `ClassNode` не загрязняется. Postcondition перепроверяется
дважды: на изменённом клоне и на повторно прочитанных сериализованных байтах. Это
match/mutate/verify с той же силой доказательства, но с granularity «класс × mask», а не
«один patchId».

Отличается envelope, а не форма:

- **Единица транзакции — класс × mask.** `requestedMaskForClass` собирает биты включённых
  компонентов; все запрошенные компоненты класса применяются одной транзакцией либо не
  применяются вовсе (`docs/PATCHES.md` §Fast-forward presentation coalescing). В per-patch
  envelope атомарная единица — один `patchId`.
- **Ownership на уровне класса.** Два private synthetic поля (`smo$patched$fastForwardPresentation`,
  `smo$fastForwardPresentationMask`) с одним owner-значением вместо per-patch маркеров
  `smo$patched$<patchId>`.
- **Loader routing.** Target и runtime hooks проверяются на принадлежность одному loader'у;
  результат `SKIPPED_LOADER` оставляет класс vanilla
  (`docs/COMPATIBILITY.md` §Classloader-архитектура и Faster Rendering, §Статусы).
- **Статусная поверхность.** Один статус `fastForwardPresentation` на класс покрывает все его
  компоненты; structural envelope публикует статус на `patchId` на класс.
- **Composition bridge.** `PresentationStructuralContract` координирует overlap-классы между
  оболочками: structural transformer принимает только полностью vanilla presentation-state
  либо точный набор owner/mask/hooks и перепроверяет его до анализа, после каждого commit и
  в финале (`docs/COMPATIBILITY.md` §Presentation и structural patches).

## Почему не альтернатива

Сворачивание в per-patch envelope разложило бы транзакцию «класс × mask» на шестнадцать
независимых per-patch транзакций. Документированная семантика — «все включённые компоненты
класса или ни один» — потребовала бы заново построить поверх них: общий mask, общие
ownership fields, объединённый статус на класс и повторное доказательство ownership каждым
компонентом при частичном применении соседей. Выигрыша в силе доказательства нет: `inspect`
уже доказывает всё, что трогает `apply`, а мутация уже транзакционна через клон. Результатом
стала бы та же машинерия в более сложной сборке.

Обратное сворачивание — перенести structural patches в декларативную таблицу — рассмотрено и
отвергнуто в [[D-PATCH-SHAPE]] §Почему не альтернатива: таблица «найти места — заменить» не
покрывает патчи, создающие обёртки, сохраняющие оригинальные тела приватными синтетическими
методами, добавляющие транзиентные поля или подставляющие скомпилированные шаблоны.

## Следствия

1. Каталог `patch.fastForward*` и семантика ключей не меняются; документированная единица —
   компонент внутри class-plan'а, а не самостоятельный `StructuredPatch`.
2. Новый presentation-компонент добавляется в `PLANS` под транзакцией своего класса; для
   класса из overlap-set дополнительно расширяется `PresentationStructuralContract`.
3. Новый патч вне presentation surfaces реализуется как `StructuredPatch` под `apply(...)`.
4. Порядок `presentation → structural` и перепроверка presentation state со стороны
   structural transformer'а остаются runtime-контрактом, а не следствием порядка
   регистрации stage'ей в `OrderedTransformerPipeline`.

## Что этим решением не разрешено

- Не решено, нужна ли когда-либо per-patch атомарность внутри presentation-класса; текущий
  контракт намеренно class-level, и переход на per-component транзакции был бы изменением
  документированной семантики.
- Не затронута форма составных `PatchAction` structural envelope'а — планов, оркестрирующих
  несколько `StructuredPatch` или меняющих shared-infrastructure surface: это отдельный
  вопрос.
- Не закрыт вопрос per-component диагностики: статус и ownership остаются на уровне класса.

## Проверка

`verify-structural` покрывает обе оболочки одним проверяемым multiset: per-class статусы
`fastForwardPresentation` и per-patch статусы structural transformer'а извлекаются и
сравниваются вместе, а tamper-регрессии на overlap-классах проверяют
`PresentationStructuralContract` в обоих направлениях — marker без hooks, hooks без marker,
неверная mask и повреждение wrapper дают `SKIPPED_COMPOSITION`.
