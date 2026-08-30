# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Текущая версия: **0.18.4**. Поддерживаемая версия игры: **Starsector 0.98a-RC8**.

[![Без препатчера и с ним](media/smoothness_comparison.gif)](https://github.com/kirpoly/StarsectorPrepatcher/releases/download/v0.8.0/StarsectorPrepatcher-0.8.0-comparison.webm)

Набор оптимизаций внутреннего кода Starsector и избранных модов.

## Установка

1. Закройте Starsector.
2. Распакуйте мод как `<Starsector>\mods\StarsectorPrepatcher`.
3. Установите javaagent. Для ваниллы добавьте следующее в `<Starsector>\vmparams` в существующую строку аргументов, после остальных `-javaagent` и перед `-classpath`:

   ```text
   -javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar
   ```

   **ИЛИ**

   Запустите входящий в поставку `StarsectorPrepatcher.bat`, выберите **Install javaagent** и ответьте yes/no на запросы по каждому пути запуска. Он выполнит правку автоматически с резервными копиями с меткой времени, а также поддерживает [Faster Rendering](https://github.com/Halke1986/starsector-render) (`fr.vmparams`) и [лаунчер Mikohime](https://github.com/GaiusCassiusL/Starsector_Mikohime-Unofficial-Java28-Configurator) (Java 27+) — для них используйте .bat или разберитесь самостоятельно.
4. Включите **StarsectorPrepatcher** в launcher (рекомендуется — отображает статус патчей в логе игры) и запустите игру.
5. Если вы используете **AoTD — Theory of Toolbox**, дополнительно установите поддерживаемый мод [Scheduler Fork](https://github.com/cyrrp/AoTD-Theory-Of-Toolbox-Scheduler-Fork) (выпуск `1.0.14-spp13`) и включите его. Без него исправления производительности рынков не применяются при установленном AoTD.

Для удаления: запустите `StarsectorPrepatcher.bat` и выберите **Remove javaagent** (он создаёт резервную копию каждого файла перед изменением, с меткой времени), либо удалите запись `-javaagent` вручную.

StarsectorPrepatcher распространяется на условиях, указанных в [`LICENSE`](LICENSE).
