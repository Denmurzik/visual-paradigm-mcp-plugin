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
| `vp_create_diagram` | Создание диаграммы любого типа |
| `vp_build_diagram` | Много фигур и связей за один вызов, со ссылками по своим ключам |
| `vp_add_shape`, `vp_add_connector`, `vp_add_child` | Отдельные элементы, связи, члены классов (атрибуты, операции, колонки и т. п.) |
| `vp_update_element`, `vp_delete`, `vp_set_bounds` | Переименование, свойства, удаление (из модели или только с диаграммы), перемещение вместе со связями |
| `vp_set_use_case_details` | Пред- и постусловия, акторы, потоки событий |
| `vp_cleanup_extension_points` | Удаление точек «ExtensionPoint», которые VP добавляет к каждому Extend |
| `vp_layout_diagram`, `vp_open_diagram` | Авторазметка (`boundary` для границ системы, `reroute` — перерисовать только связи), показ диаграммы в VP |
| `vp_export_diagram_image` | Экспорт в PNG/JPG/SVG/PDF; PNG можно вернуть ИИ, чтобы он посмотрел на результат |

Направление связей всегда задаётся как в UML: потомок → родитель для Generalization,
класс → интерфейс для Realization, расширение → базовый вариант использования для Extend.
Плагин сам переводит его во внутреннее направление Visual Paradigm.

Свойства — это любые сеттеры объекта модели Visual Paradigm без префикса `set` (`visibility`,
`multiplicity`, `abstract`, `primaryKey`, `guard`, ...). Числовые перечисления принимают имена
констант, префиксы `from.`/`to.` обращаются к концам ассоциации.

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

#### Из релиза (без инструментов сборки)

1. Установите [Visual Paradigm](https://www.visual-paradigm.com/download/) 18.1 или новее
   (подходит Community Edition).
2. Скачайте `visual-paradigm-mcp-plugin-<версия>.zip` со страницы
   [Releases](../../releases).
3. Закройте Visual Paradigm и распакуйте архив в папку плагинов так, чтобы получилось
   `<plugins>/visual-paradigm-mcp-plugin/plugin.xml`:
   - Windows: `%APPDATA%\VisualParadigm\plugins`
   - Linux: `~/.config/VisualParadigm/plugins`
4. Запустите Visual Paradigm. В `vp.log` (Windows: `%APPDATA%\VisualParadigm\vp.log`) появится
   строка `[vp-mcp] MCP server listening on http://127.0.0.1:8931/mcp`.
5. Подключите MCP-клиент (см. ниже).

Проверено на Windows 11 с Visual Paradigm Community Edition 18.1.

#### Из исходников

Сборка, тесты и установка выполняются командой `./run`. Если Maven не в `PATH`, укажите
`MVN=/путь/к/mvn`. Дополнительные аргументы передаются в Maven, например
`./run install -Dvp.home="D:/VP 18.1"`, если Visual Paradigm установлен не в
`C:/Program Files/Visual Paradigm CE 18.1`:

1. **Собрать плагин:**

   ```bash
   ./run build
   ```

2. **Упаковать дистрибутив:**

   ```bash
   ./run package
   ```

3. **Установить в Visual Paradigm** (сначала закройте Visual Paradigm, она блокирует jar-файлы
   плагина):

   ```bash
   ./run install
   ```

4. **Запустить Visual Paradigm** — MCP-сервер стартует автоматически.

### Работа с MCP-сервером

Когда Visual Paradigm запущена с плагином:

- **Адрес MCP-сервера:** `http://127.0.0.1:8931/mcp` (Streamable HTTP)
- **Имя сервера:** `visual-paradigm`
- **Инструментов:** 22 (см. выше)

#### Подключение Claude и других MCP-клиентов

Claude Code:

```bash
claude mcp add --transport http -s user visual-paradigm http://127.0.0.1:8931/mcp
```

Другие клиенты:

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

Сервер работает только пока открыта Visual Paradigm. Claude Code загружает список инструментов
при старте сессии, поэтому после установки или обновления плагина перезапустите сессию.

Советы: просите ИИ проверять результат через `vp_export_diagram_image`; сохраняйте проект через
`vp_save_project` (изменения не сохраняются сами); закрывайте диалоги, которые открывает Visual
Paradigm, — пока открыт модальный диалог, изменения отклоняются с сообщением «busy».

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

- `MCP server listening on http://127.0.0.1:8931/mcp (22 tools)` — сервер запущен
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
