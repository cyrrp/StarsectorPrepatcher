# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Текущая версия: **0.18.7**. Поддерживаемые сборки игры: **Starsector 0.98a-RC8 и 0.98.5a-RC3** (Windows/Linux).
В metadata указана 0.98.5a-RC3; на поддерживаемой 0.98a-RC8 лаунчер может показывать отметку о другой патч-версии.

[![Без препатчера и с ним](https://github.com/kirpoly/StarsectorPrepatcher/raw/HEAD/media/smoothness_comparison.gif)](https://github.com/kirpoly/StarsectorPrepatcher/releases/download/v0.8.0/StarsectorPrepatcher-0.8.0-comparison.webm)

Набор оптимизаций внутреннего кода Starsector и избранных модов.

Для Starsector 0.98.5a используйте Faster Rendering 1.0.0 или новее. После обновления Faster Rendering заново создайте конфигурацию Mikohime.

## Установка в Windows

1. Закройте Starsector.
2. Распакуйте мод как `<Starsector>\mods\StarsectorPrepatcher`.
3. Установите javaagent. Для ваниллы добавьте следующее в `<Starsector>\vmparams` в существующую строку аргументов, после остальных `-javaagent` и перед `-classpath`:

   ```text
   -javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar
   ```

   **ИЛИ**

   Запустите входящий в поставку `StarsectorPrepatcher.bat`, выберите **Install javaagent** и укажите используемые пути запуска. Он автоматически правит ваниллу, отдельный лаунчер [Faster Rendering](https://github.com/Halke1986/starsector-render) (`fr.vmparams`) и оригинальный Mikohime, создавая резервные копии с меткой времени. Оригинальный Mikohime перед установкой агента нужно один раз настроить.

   В [конфигураторе Mikohime от Gaius](https://github.com/GaiusCassiusL/Starsector_Mikohime-Unofficial-Java28-Configurator) поддержка Prepatcher уже встроена: включите там **StarsectorPrepatcher**, создайте конфигурацию и запускайте игру через `Miko_Rouge.bat` или `Miko_Silent.bat`, если он доступен. Наш установщик распознаёт эту интеграцию и показывает инструкцию без правки её файлов. Если Faster Rendering запускается через Mikohime, достаточно выбрать **Mikohime**; отдельная цель **FasterRendering** для этого пути не нужна.
4. Включите **StarsectorPrepatcher** в launcher (рекомендуется — отображает статус патчей в логе игры) и запустите игру.
5. Если вы используете **AoTD — Theory of Toolbox**, дополнительно установите поддерживаемый мод [Scheduler Fork](https://github.com/cyrrp/AoTD-Theory-Of-Toolbox-Scheduler-Fork) (выпуск `1.0.14-spp13`) и включите его. Без него исправления производительности рынков не применяются при установленном AoTD.

Для удаления: в Mikohime от Gaius со встроенной интеграцией отключите **StarsectorPrepatcher** через `Configure_Me.cmd` и пересоздайте конфигурацию перед удалением папки мода. Для остальных путей запуска используйте `StarsectorPrepatcher.bat` → **Remove javaagent** (он создаёт резервную копию каждого изменяемого файла с меткой времени), либо удалите запись `-javaagent` вручную.

## Установка в Linux

1. Закройте Starsector.
2. Распакуйте релизный ZIP как `<Starsector>/mods/StarsectorPrepatcher`.
3. Установите javaagent (Java 17+). Для ваниллы сохраните копию `<Starsector>/starsector.sh` и добавьте строку в существующую команду Java, после остальных `-javaagent` и перед `-classpath`. Сохраните завершающий `\`:

   ```sh
   -javaagent:./mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar \
   ```

   **ИЛИ**

   Для Mikohime/Faster Rendering с `Miko_Simple.txt` используйте входящий в поставку лаунчер из каталога игры:

   ```sh
   JAVA_HOME=/path/to/your/java bash ./mods/StarsectorPrepatcher/StarsectorPrepatcher.sh
   ```

   Укажите в `JAVA_HOME` Java из вашей установки Mikohime или уберите переменную, если эта Java доступна через `PATH`.
4. Включите **StarsectorPrepatcher** в launcher (рекомендуется — отображает статус патчей в логе игры). Для ваниллы запускайте `./starsector.sh` из каталога игры.
5. Если вы используете **AoTD — Theory of Toolbox**, дополнительно установите поддерживаемый мод [Scheduler Fork](https://github.com/cyrrp/AoTD-Theory-Of-Toolbox-Scheduler-Fork) (выпуск `1.0.14-spp13`) и включите его. Без него исправления производительности рынков не применяются при установленном AoTD.

Для удаления: уберите добавленную строку `-javaagent` из `starsector.sh` либо вернитесь к обычному лаунчеру Mikohime.

StarsectorPrepatcher распространяется на условиях, указанных в [`LICENSE`](LICENSE).
