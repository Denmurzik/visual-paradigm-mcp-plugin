# Visual Paradigm MCP Plugin

[English](README.md) | **Русский**

> Форк [orgatex/visual-paradigm-mcp-plugin](https://github.com/orgatex/visual-paradigm-mcp-plugin)
> (автор Manoel Brunnen), переписанный под Visual Paradigm 18.1, включая бесплатную Community
> Edition: без Spring, работает на встроенной в VP Java 11 и поддерживает все типы UML-диаграмм.

Плагин для Visual Paradigm со встроенным MCP-сервером (Model Context Protocol). Через него
ИИ-ассистенты, например Claude, могут создавать и редактировать модели и диаграммы в Visual
Paradigm. Сервер запускается автоматически при загрузке плагина.

## Архитектура

- **Visual Paradigm 18.1** (в том числе Community Edition)
- **Java 11, без сторонних зависимостей.** Visual Paradigm запускает плагины на встроенной
  Java 11, в которой нет модуля `jdk.httpserver`. Поэтому в плагине свой небольшой HTTP-сервер,
  чтение и запись JSON и слой MCP (JSON-RPC).
- **Транспорт MCP:** Streamable HTTP (POST, ответы в JSON), только на `127.0.0.1`
- **Visual Paradigm Plugin API:** все обращения к модели выполняются в потоке Swing (EDT),
  каждое изменение — одна отменяемая транзакция проекта
- **Maven:** сборка
- **JUnit 5:** тесты слоёв HTTP, JSON, MCP и reflection

## Возможности

### MCP-сервер

Встроенный MCP-сервер:

- **запускается** вместе с плагином при старте Visual Paradigm;
- **останавливается** при выгрузке плагина;
- работает на **порту 8931**, адрес `/mcp` (порт меняется в `mcp.properties` в папке
  установленного плагина или параметром `-Dvp.mcp.port=...`);
- предоставляет MCP-клиентам **инструменты (tools)**.

#### Инструменты MCP

Инструменты универсальные: типы элементов и связей — это имена типов модели Visual Paradigm,
поэтому поддерживается любой тип диаграмм. Проверены диаграммы вариантов использования,
активности (с дорожками), последовательности, классов, ER и состояний.

| Инструмент | Назначение |
| --- | --- |
| `vp_get_project_info` | Открытый проект: имя, файл, несохранённые изменения, версия VP |
| `vp_new_project`, `vp_open_project`, `vp_save_project` | Работа с файлами проекта |
| `vp_list_diagrams`, `vp_get_diagram` | Диаграммы, их фигуры и связи |
| `vp_find_elements`, `vp_get_element` | Поиск по модели, свойства, связи, потоки событий |
| `vp_list_types` | Типы фигур, допустимые на диаграмме |
| `vp_get_view` | Свойства отображения фигуры, связи или диаграммы (ключи для `view`) |
| `vp_create_diagram` | Создание диаграммы любого типа |
| `vp_build_diagram` | Много фигур и связей за один вызов, со ссылками по своим ключам |
| `vp_build_sequence` | Полная диаграмма последовательности по участникам и шагам: полосы активации, create/destroy, вызов самого себя, асинхронные сообщения и ответы, комбинированные фрагменты (alt, opt, loop, par, break, ...) с условиями, ссылки ref, рамка `sd` |
| `vp_add_shape`, `vp_add_connector`, `vp_add_child` | Отдельные элементы, связи, члены классов (атрибуты, операции, колонки и т. п.) |
| `vp_update_element`, `vp_delete`, `vp_set_bounds` | Переименование, свойства, удаление (из модели или только с диаграммы), перемещение вместе со связями |
| `vp_set_use_case_details` | Пред- и постусловия, акторы, потоки событий |
| `vp_cleanup_extension_points` | Удаление точек «ExtensionPoint», которые VP добавляет к каждому Extend |
| `vp_layout_diagram`, `vp_open_diagram` | Авторазметка (`layered` для диаграмм классов и ER, `boundary` для границ системы, `reroute` — перерисовать только связи), показ диаграммы в VP |
| `vp_list_dialogs`, `vp_press_dialog_button` | Просмотр диалогов Visual Paradigm (сохранение, восстановление проекта и т. п.) и ответ на них |
| `vp_export_diagram_image` | Экспорт в PNG/JPG/SVG/PDF; PNG можно вернуть ИИ, чтобы он посмотрел на результат |

Направление связей всегда задаётся как в UML: потомок → родитель для Generalization,
класс → интерфейс для Realization, расширение → базовый вариант использования для Extend.
Плагин сам переводит его во внутреннее направление Visual Paradigm.

Свойства — это любые сеттеры объекта модели Visual Paradigm без префикса `set` (`visibility`,
`multiplicity`, `abstract`, `primaryKey`, `guard`, ...). Числовые перечисления принимают имена
констант, префиксы `from.`/`to.` обращаются к концам ассоциации.

Модели классов: `Interface`, `Enumeration` (с литералами `EnumerationLiteral`), `DataType`;
атрибуты, операции с параметрами, статические и абстрактные члены, начальные значения; роли,
кратности и навигируемость концов ассоциации, агрегация и композиция, квалификаторы
(`"to.qualifier": "isbn: String"`), классы-ассоциации; стереотипы у любого элемента
(`stereotypes`, `removeStereotypes`). Тип может ссылаться на класс по имени, id или `@key` из того
же вызова `vp_build_diagram`. На элементы можно ссылаться по id или по уникальному имени.
Параметр `view` меняет вид фигуры, например `{"displayStereotypeIcon": false}` показывает классы
`«entity»` прямоугольником, а не значком робастности.

#### Планы

##### Ресурсы

- Статус сервера: состояние MCP-сервера и подключения
- Информация о проекте: сведения о текущем проекте
- Метаданные диаграмм: свойства и структура диаграмм

##### Промпты

- Шаблоны вариантов использования
- Проверка диаграмм на полноту и согласованность

### Интеграция с Visual Paradigm

- **Жизненный цикл:** MCP-сервер запускается и останавливается вместе с плагином
- **Ошибки:** проблемы запуска (например, занятый порт) видны в панели сообщений Visual
  Paradigm и в `vp.log`; ошибки инструментов возвращаются клиенту понятным текстом
- **Безопасность:** сервер слушает только локальный интерфейс и отклоняет запросы со сторонних
  сайтов (non-local Origin)

## Использование

### Установка

Три шага, одинаковые для всех ОС: **установить плагин** в Visual Paradigm, **подключить ИИ-клиент**,
**установить скилл**. Для релиза не нужны ни Java, ни Maven — Visual Paradigm приносит свою среду.

#### 1. Установка плагина

1. Установите [Visual Paradigm](https://www.visual-paradigm.com/download/) 18.1 или новее
   (подходит бесплатная Community Edition).
2. Скачайте `visual-paradigm-mcp-plugin-<версия>.zip` со страницы [Releases](../../releases) и
   распакуйте. Получится папка `visual-paradigm-mcp-plugin` с файлом `plugin.xml`.
3. В Visual Paradigm откройте **Help > Install Plugin**, выберите **Install from a folder of
   plugin** и укажите папку `visual-paradigm-mcp-plugin`. Перезапустите Visual Paradigm.

   Или скопируйте папку вручную, пока Visual Paradigm закрыта. Точную папку плагинов на вашем
   компьютере показывает **Help > Install Plugin > Copy Path**; обычно это:

   | ОС | Папка плагинов |
   | --- | --- |
   | Windows | `%APPDATA%\VisualParadigm\plugins` |
   | macOS | `~/Library/Application Support/VisualParadigm/plugins` |
   | Linux | `~/.config/VisualParadigm/plugins` (в старых версиях `~/VisualParadigm/plugins`) |

   В итоге должно получиться `<папка плагинов>/visual-paradigm-mcp-plugin/plugin.xml`.
4. Запустите Visual Paradigm. В журнале `vp.log` в пользовательской папке Visual Paradigm (на
   уровень выше папки плагинов, на Windows `%APPDATA%\VisualParadigm\vp.log`) появится строка
   `[vp-mcp] MCP server listening on http://127.0.0.1:8931/mcp (26 tools)`.

Для обновления закройте Visual Paradigm, замените папку `visual-paradigm-mcp-plugin` и запустите
её снова (пока Visual Paradigm открыта, она блокирует jar-файл плагина).

Проверено на Windows 11 с Visual Paradigm Community Edition 18.1. Плагин — обычная Java 11 без
нативного кода, поэтому на macOS и Linux всё работает так же, различаются только папки.

#### Из исходников

Нужны JDK 11 или новее, Maven 3.9 и установленная Visual Paradigm (сборка компилируется против
её `openapi.jar`).

Сборка, тесты и установка выполняются командой `./run` (bash; на Windows — Git Bash или прямой
вызов Maven с теми же аргументами). Если Maven не в `PATH`, укажите `MVN=/путь/к/mvn`.
Дополнительные аргументы передаются в Maven:

| Свойство | Что это | По умолчанию |
| --- | --- | --- |
| `vp.lib.dir` | папка с `openapi.jar` (`lib` или `bundled` в установке VP) | Windows: `C:/Program Files/Visual Paradigm CE 18.1/lib`, Linux: `~/Visual_Paradigm_18.1/lib` |
| `vp.plugins.dir` | папка плагинов Visual Paradigm для `./run install` | Windows: `%APPDATA%/VisualParadigm/plugins`, Linux/macOS: `~/.config/VisualParadigm/plugins` |

```bash
# Windows (Git Bash), установка по умолчанию
./run all

# macOS
./run all -Dvp.lib.dir="/Applications/Visual Paradigm.app/<путь к папке с openapi.jar>" \
          -Dvp.plugins.dir="$HOME/Library/Application Support/VisualParadigm/plugins"

# Linux
./run all -Dvp.lib.dir="$HOME/Visual_Paradigm_18.1/lib"
```

`./run all` собирает, запускает тесты и устанавливает; `./run build`, `./run test`, `./run package` и
`./run install` выполняют отдельные шаги. Перед установкой закройте Visual Paradigm.

### Работа с MCP-сервером

Когда Visual Paradigm запущена с плагином:

- **Адрес MCP-сервера:** `http://127.0.0.1:8931/mcp` (Streamable HTTP)
- **Имя сервера:** `visual-paradigm`
- **Инструментов:** 26 (см. выше)

#### 2. Подключение ИИ-клиента

Visual Paradigm должна быть запущена с плагином; сервер работает по адресу
`http://127.0.0.1:8931/mcp` (Streamable HTTP). Зарегистрируйте его один раз в своём клиенте:

| Клиент | Команда |
| --- | --- |
| Claude Code | `claude mcp add --transport http -s user visual-paradigm http://127.0.0.1:8931/mcp` |
| Codex CLI | `codex mcp add visual-paradigm --url http://127.0.0.1:8931/mcp` |
| OpenCode | `opencode mcp add visual-paradigm --url http://127.0.0.1:8931/mcp` |
| Antigravity CLI (`agy`) | `agy mcp add visual-paradigm http://127.0.0.1:8931/mcp` |

Проверка: `claude mcp list`, `codex mcp list`, `opencode mcp list` или `agy mcp list`. Все четыре
клиента проверены с этим сервером (Claude Code, Codex CLI 0.160, OpenCode 1.18,
Antigravity CLI 1.2).

Другие клиенты настраиваются обычным JSON-конфигом:

```json
{
  "mcpServers": {
    "visual-paradigm": {
      "type": "http",
      "url": "http://127.0.0.1:8931/mcp"
    }
  }
}
```

Клиенты загружают список инструментов при старте сессии: после установки или обновления плагина
перезапустите сессию. Сервер работает только пока открыта Visual Paradigm.

#### 3. Установка скилла (рекомендуется)

MCP-сервер сообщает модели, что делает каждый инструмент, а **скилл `visual-paradigm`** из
[`skills/visual-paradigm`](skills/visual-paradigm) — как с ними хорошо работать: какой инструмент
брать для какой диаграммы, направления связей в UML, проверка результата по картинке, сохранение,
ответы на диалоги Visual Paradigm и проверенные примеры для каждого типа диаграмм. Установите его
вместе с MCP-сервером — папка без изменений подходит всем четырём клиентам:

| Клиент | Куда скопировать `skills/visual-paradigm` |
| --- | --- |
| Claude Code | `~/.claude/skills/visual-paradigm` (или `.claude/skills/` в проекте) |
| Codex CLI | `~/.codex/skills/visual-paradigm` |
| OpenCode | `~/.config/opencode/skills/visual-paradigm` |
| Antigravity CLI | `~/.gemini/antigravity/skills/visual-paradigm` (или `.agent/skills/` в рабочей папке) |

Команды (из клона репозитория или после распаковки `visual-paradigm-skill.zip` из релиза — тогда
вместо `skills/visual-paradigm` укажите `visual-paradigm`):

macOS / Linux:

```bash
mkdir -p ~/.claude/skills && cp -r skills/visual-paradigm ~/.claude/skills/                    # Claude Code
mkdir -p ~/.codex/skills && cp -r skills/visual-paradigm ~/.codex/skills/                      # Codex CLI
mkdir -p ~/.config/opencode/skills && cp -r skills/visual-paradigm ~/.config/opencode/skills/  # OpenCode
mkdir -p ~/.gemini/antigravity/skills && cp -r skills/visual-paradigm ~/.gemini/antigravity/skills/  # Antigravity
```

Windows (PowerShell):

```powershell
foreach ($d in "$HOME\.claude\skills", "$HOME\.codex\skills",
               "$HOME\.config\opencode\skills", "$HOME\.gemini\antigravity\skills") {
  New-Item -ItemType Directory -Force $d | Out-Null
  Copy-Item -Recurse -Force skills\visual-paradigm $d
}
```

Копируйте только в те клиенты, которыми пользуетесь. После этого перезапустите сессию клиента.

## Разработка

### Сборка

```bash
./run build
./run test
./run package
./run all          # сборка, тесты, упаковка и установка
```

### Тестирование

- **Юнит-тесты:** JSON, HTTP-сервер (сокеты, chunked-тела, keep-alive), обработка MCP JSON-RPC
  и установка свойств через reflection
- **Ручные тесты:** работа с Visual Paradigm через инструменты MCP

#### Юнит-тесты

```bash
./run test
```

#### Проверка протокола MCP

```bash
curl -s -X POST http://127.0.0.1:8931/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### Отладка

**Журнал MCP-сервера:** ищите в `%APPDATA%\VisualParadigm\vp.log` (Windows) строки,
начинающиеся с `[vp-mcp]`:

- `MCP server listening on http://127.0.0.1:8931/mcp (26 tools)` — сервер запущен
- `MCP server stopped` — сервер остановлен
- `MCP server could not start on port ...` — например, порт занят другой программой

**Настройки:** `mcp.properties` в папке установленного плагина

```properties
port=8931
```

### Документация

- **Протокол MCP:** [Model Context Protocol Specification](https://modelcontextprotocol.io/specification/2025-06-18/architecture)
- **Visual Paradigm:** [Plugin API Documentation](https://www.visual-paradigm.com/support/documents/pluginjavadoc/)

## Лицензия

Apache License 2.0, см. [LICENSE](LICENSE) и [NOTICE](NOTICE). Visual Paradigm — товарный знак
Visual Paradigm International; проект с ней не связан. Visual Paradigm Open API (`openapi.jar`)
в репозиторий не входит: при сборке он берётся из локально установленной Visual Paradigm.
