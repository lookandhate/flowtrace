# Flow Trace for Go

Плагин для GoLand 2026.3+: ПКМ на переменной, параметре, получателе или поле.

- **Откуда пришло значение** — самые верхние источники (литералы, конструкторы, внешние вызовы) и цепочка через параметры, поля, `return` и вызовы через интерфейсы.
- **Куда уходит значение** — через какие параметры, поля и методы проходит значение и где оно используется в итоге.

Результат открывается в окне Flow Trace: дерево (источники с цепочками и полное дерево) и граф. Двойной щелчок переходит к коду. Горячие клавиши: `Ctrl+Alt+Shift+O` и `Ctrl+Alt+Shift+U`.

## Сборка

Нужна Java 25: GoLand 2026.3 собрана под неё. Подойдёт JBR из установленной GoLand:

```
export JAVA_HOME=~/Applications/GoLand.app/Contents/jbr/Contents/Home
./gradlew test buildPlugin verifyPlugin
```

Если JDK 25 на машине нет, Gradle скачает её сам (foojay), но для запуска `./gradlew` всё равно нужна какая-нибудь Java в `JAVA_HOME`.

По умолчанию сборка скачивает GoLand версии `platformVersion` из `gradle.properties`. Чтобы собирать против установленной GoLand (быстрее и точно под вашу сборку), укажите путь в `~/.gradle/gradle.properties` — этот файл вне репозитория:

```
goLandPath=/Users/<you>/Applications/GoLand.app
```

Архив: `build/distributions/flowtrace-<версия>.zip`. Установка: Settings → Plugins → ⚙ → Install Plugin from Disk.

## Устройство

Kotlin, IntelliJ Platform Gradle Plugin 2.x, версии зависимостей — в `gradle/libs.versions.toml`.

- `analysis` — анализ потока по PSI Go-плагина (`GoFlowAnalyzer`), выполняется в read action;
- `model` — граф (`FlowGraph`, `FlowNode`, `FlowEdge`), после анализа PSI не нужен, узлы держат smart pointer;
- `services/FlowTraceService` — project-сервис: `withBackgroundProgress` + `smartReadAction` в scope сервиса;
- `actions` — пункты контекстного меню (обновление на BGT);
- `ui` — окно Flow Trace: дерево и граф;
- все тексты — в `messages/FlowTraceBundle.properties`.

## Ограничения

- Анализ не различает экземпляры: поле структуры — один узел на все объекты, вызов через интерфейс ведёт во все реализации в проекте.
- В библиотеки (stdlib, модули из `go.mod`, соседние репозитории через `replace`, если они не открыты как часть проекта) не заходит: такой вызов — внешний источник или конечная точка.
- Не разбираются позиционные литералы структур (`Store{cfg}`), параметры анонимных функций и значения, прошедшие через каналы, map и срезы.
- Лимит — 400 узлов и глубина 40; обрезанные узлы помечены.
