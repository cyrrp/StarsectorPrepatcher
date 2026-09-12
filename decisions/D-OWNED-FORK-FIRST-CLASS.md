> Project home:
> [StarsectorPrepatcher — overview, installation and downloads](https://github.com/kirpoly/StarsectorPrepatcher)

---

# D-OWNED-FORK-FIRST-CLASS — форк AoTD не является неизвестным сторонним subclass

**Статус:** ACTIVE
**Установлено:** `0.11.0`, вместе с AoTD Scheduler Fork
**Governs:** `docs/COMPATIBILITY.md` §Owned fork как first-class compatibility surface,
§Owned AoTD market-share subclass, §Owned AoTD economy и construction surfaces,
§AoTD Scheduler Fork; `AGENTS.md` §Owned AoTD fork compatibility

## Вопрос

AoTD наследует vanilla-классы, которые патчит агент. Стандартный приём — exact-class guard: применять
оптимизацию только когда runtime-класс ровно vanilla, иначе raw path. Но форк AoTD Scheduler Fork
поддерживается этим же проектом, его исходники и JAR доступны. Считать ли его неизвестным subclass,
которому по умолчанию отказано?

## Решение

Форк — обязательная compatibility surface, наравне с vanilla. Перед добавлением exact-class guard
или изменением кратности виртуальных вызовов проводится inventory subclass'ов и override'ов по
реальному исходнику/JAR форка. Допустимы ровно четыре документированных исхода для затронутого
класса:

1. унаследованный оптимизированный путь — когда семантическая поверхность идентична vanilla;
2. отдельная трансформация метода, которым владеет форк, — когда реализация другая, но поддержанная;
3. соответствующего subclass нет — трансформация не нужна;
4. локальный fail-closed raw fallback для конкретного runtime-класса ([[D-FAIL-OPEN]]).

## Почему не альтернатива

Exact-class guard по умолчанию отдал бы AoTD-пользователям vanilla-производительность именно в тех
местах, ради которых агент существует: рыночные hot paths при AoTD нагружены сильнее, чем без него.
Name-only allowlist — противоположная ошибка: он объявляет форк совместимым по строке имени, поэтому
обновление форка, добавившее override, тихо получит семантику, рассчитанную на другой код.

## Следствия

1. Отчёт валидации обязан указывать для каждого затронутого класса, каким из четырёх исходов
   покрыт форк. «Vanilla-тесты прошли» не является завершённостью патча.
2. Проверки используют реальный JAR форка, не name-only stub.
3. Обязателен negative fixture будущего override: обновление форка не может тихо получить
   небезопасную семантику.
4. Состояние совместимости — loader-safe: без статических strong `Class`, `Method`, `ClassLoader`,
   campaign-объектов и mod-instance кэшей. Решение хранится в `ClassValue`, чтобы дочерний
   classloader не становился strong root.

## Что этим решением не разрешено

Распространение того же режима на сторонние моды, которые проект не поддерживает: для них
остаётся обычный guard и raw path. Решение опирается на то, что исходник форка доступен и
обновляется вместе с агентом; при потере этого условия решение подлежит пересмотру, а не
расширению по аналогии.

## Проверка

`docs/VALIDATION.md` §Обязательные review gates и §Обязательные test gates; regression-прогон с
реальным JAR форка и future-override fixture.
