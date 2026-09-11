> Project home:
> [StarsectorPrepatcher — overview, installation and downloads](https://github.com/kirpoly/StarsectorPrepatcher)

---

# Незавершённые работы

Только то, что не сделано. Пункт снимается, когда работа выпущена. Текущее устройство продукта — в
[`PATCHES.md`](PATCHES.md), [`COMPATIBILITY.md`](COMPATIBILITY.md) и
[`VALIDATION.md`](VALIDATION.md); состояние кода читается из кода.

## P0

- [ ] **Structural discovery.** Цели находятся по структуре class-файлов до загрузки; точные
  obfuscated-имена не участвуют в решении о совместимости
  ([D-STRUCTURAL-DISCOVERY](../decisions/D-STRUCTURAL-DISCOVERY.md)). Бюджет на референсной
  установке (~6400 class-файлов, ~14 МБ): не более 128 кандидатов на полный разбор, p95 до 1 с
  warm и 2 с cold, retained менее 2 МБ.
- [ ] **Linux Tier 1.** `install-agent.sh` и `uninstall-agent.sh`, adapters для официального
  `starsector.sh` и пользовательских `jvm_args`, startup smoke как blocking gate.
- [ ] **Полный structural gate на реальной игре.** Self-hosted Windows и Linux runners;
  проприетарные JAR не попадают в артефакты и cache.

## P1

- [ ] **Разделение `inspect` / `apply` / `verify`.** Matcher доказывает контракт целиком до того,
  как mutator меняет `ClassNode`; `PrepatcherTransformer` остаётся без реализаций патчей.
- [ ] **Единый каталог целей.** `TARGET_CLASSES`, dispatch-switch, config-switch и отдельный
  hyperspace-список сводятся в один источник.
- [ ] **Hyperspace на общем patch API.** Второй вариант API и дублирующие ASM helpers убираются.
- [ ] **Атрибуты репозитория.** `.editorconfig`, `.gitattributes`, mode `100755` для `.sh`.
  Форматтер уже есть: Eclipse JDT 3.41.0 с конфигом `tools/format/eclipse-jdt-format.properties`,
  библиотеки качаются по требованию с проверкой SHA-256, первый механический reformat выполнен.
- [ ] **Публичный CI без игровых JAR.** Formatter, line endings, synthetic discovery, installer
  fixtures.
- [ ] **Воспроизводимый релизный артефакт.** Manifest, фиксированный порядок entries,
  `SOURCE_DATE_EPOCH`, покрытие `SHA256SUMS.txt`.

## P3

- [ ] **macOS.** Synthetic-проверки и ручная инструкция по установке. Выпуск не блокирует.
