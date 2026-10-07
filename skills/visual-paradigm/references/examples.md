# Examples (tested with Visual Paradigm 18.1)

Arguments for the vp_* tools. Keys (`"key"`) are your own names, valid inside one call.

## Contents
- [Use case diagram](#use-case-diagram)
- [Use case specification](#use-case-specification)
- [Class diagram](#class-diagram)
- [Sequence diagram](#sequence-diagram)
- [Activity diagram with swimlanes](#activity-diagram-with-swimlanes)
- [State machine](#state-machine)
- [ER diagram](#er-diagram)
- [Type names](#type-names)

## Use case diagram

`vp_build_diagram`:

```json
{
  "diagramType": "UseCaseDiagram", "name": "Конструктор чат-ботов",
  "elements": [
    {"key": "sys", "type": "System", "name": "Конструктор чат-ботов"},
    {"key": "user", "type": "Actor", "name": "Пользователь"},
    {"key": "admin", "type": "Actor", "name": "Администратор"},
    {"key": "create", "type": "UseCase", "name": "Создать бота", "parent": "sys"},
    {"key": "edit", "type": "UseCase", "name": "Редактировать сценарий", "parent": "sys"},
    {"key": "login", "type": "UseCase", "name": "Авторизоваться", "parent": "sys"},
    {"key": "export", "type": "UseCase", "name": "Экспортировать бота", "parent": "sys"}
  ],
  "connectors": [
    {"type": "Association", "from": "user", "to": "create"},
    {"type": "Association", "from": "user", "to": "edit"},
    {"type": "Include", "from": "create", "to": "login"},
    {"type": "Extend", "from": "export", "to": "edit"},
    {"type": "Generalization", "from": "admin", "to": "user"}
  ],
  "layout": "auto"
}
```

`layout: "auto"` uses the boundary layout here: actors on the left, use cases in a grid inside
the System.

## Use case specification

`vp_set_use_case_details` (ids from the build result or `vp_find_elements`; names work when
unique):

```json
{
  "useCase": "Создать бота",
  "preConditions": "Пользователь авторизован",
  "postConditions": "Бот создан и открыт в редакторе",
  "primaryActors": ["Пользователь"],
  "flows": [
    {"name": "Основной поток", "steps": ["Пользователь нажимает «Создать»",
                                          "Система открывает пустой сценарий"]},
    {"name": "Альтернативный поток: лимит ботов", "steps": ["Система сообщает о лимите"]}
  ]
}
```

## Class diagram

`vp_build_diagram`:

```json
{
  "diagramType": "ClassDiagram", "name": "Заказы",
  "elements": [
    {"key": "base", "type": "Class", "name": "BaseEntity", "properties": {"abstract": true},
     "children": [{"type": "Attribute", "name": "id",
                   "properties": {"type": "long", "visibility": "protected"}}]},
    {"key": "status", "type": "Enumeration", "name": "OrderStatus",
     "children": [{"type": "EnumerationLiteral", "name": "NEW"},
                  {"type": "EnumerationLiteral", "name": "PAID"}]},
    {"key": "printable", "type": "Interface", "name": "Printable",
     "children": [{"type": "Operation", "name": "print",
                   "properties": {"visibility": "public", "returnType": "void"}}]},
    {"key": "customer", "type": "Class", "name": "Customer",
     "properties": {"stereotype": "entity"}, "view": {"displayStereotypeIcon": false},
     "children": [{"type": "Attribute", "name": "name",
                   "properties": {"type": "String", "visibility": "private"}}]},
    {"key": "order", "type": "Class", "name": "Order",
     "properties": {"stereotype": "entity"}, "view": {"displayStereotypeIcon": false},
     "children": [
       {"type": "Attribute", "name": "status", "properties": {"type": "@status", "visibility": "private"}},
       {"type": "Attribute", "name": "count", "properties": {"type": "int", "scope": "classifier"}},
       {"type": "Operation", "name": "pay", "properties": {"visibility": "public", "returnType": "boolean"},
        "children": [{"type": "Parameter", "name": "amount", "properties": {"type": "double"}}]}]},
    {"key": "item", "type": "Class", "name": "OrderItem",
     "children": [{"type": "Attribute", "name": "qty", "properties": {"type": "int"}}]}
  ],
  "connectors": [
    {"type": "Generalization", "from": "customer", "to": "base"},
    {"type": "Generalization", "from": "order", "to": "base"},
    {"type": "Realization", "from": "order", "to": "printable"},
    {"type": "Association", "from": "customer", "to": "order",
     "properties": {"from.multiplicity": "1", "to.multiplicity": "0..*", "to.name": "orders"}},
    {"type": "Association", "from": "order", "to": "item",
     "properties": {"from.aggregationKind": "composite", "to.multiplicity": "1..*"}},
    {"type": "Dependency", "from": "order", "to": "status"}
  ],
  "layout": "auto"
}
```

More association options: `"to.navigable": "navigable"` / `"from.navigable": "non_navigable"`,
`"from.aggregationKind": "shared"`, qualifier `"from.qualifier": "isbn: String"`.
Association class: first the association (with a `key`), then
`{"type": "AssociationClass", "from": "<class key>", "to": "<association key>"}`.

## Sequence diagram

`vp_build_sequence`:

```json
{
  "name": "Оформление заказа", "frame": true,
  "participants": [
    {"key": "user", "name": "Покупатель", "kind": "actor"},
    {"key": "ui", "name": "form", "classifier": "OrderForm"},
    {"key": "ctl", "name": "controller", "classifier": "OrderController"},
    {"key": "order", "name": "order", "classifier": "Order"},
    {"key": "pay", "name": "PaymentService"}
  ],
  "steps": [
    {"from": "user", "to": "ui", "name": "submit()"},
    {"from": "ui", "to": "ctl", "name": "placeOrder(cart)"},
    {"from": "ctl", "to": "ctl", "name": "validate()"},
    {"from": "ctl", "to": "order", "name": "new Order()", "kind": "create"},
    {"fragment": "loop", "guard": "для каждой позиции", "steps": [
      {"from": "ctl", "to": "order", "name": "addItem(item)"},
      {"from": "order", "to": "ctl", "kind": "return"}]},
    {"fragment": "alt", "operands": [
      {"guard": "оплата прошла", "steps": [
        {"from": "ctl", "to": "pay", "name": "pay(total)"},
        {"from": "pay", "to": "ctl", "name": "ok", "kind": "return"}]},
      {"guard": "else", "steps": [
        {"from": "ctl", "to": "ui", "name": "showError()", "kind": "async"}]}]},
    {"ref": "Отправка уведомления", "covers": ["ctl", "pay"]},
    {"from": "ctl", "to": "ui", "name": "orderId", "kind": "return"},
    {"from": "ui", "to": "user", "name": "подтверждение", "kind": "return"},
    {"from": "ctl", "to": "order", "name": "«destroy»", "kind": "destroy"}
  ]
}
```

Write every call and its reply (`kind: "return"`) - activation bars come from these pairs.
Fragments nest (an operand's `steps` may contain another fragment). `sequenceNumbers: true`
numbers the messages.

## Activity diagram with swimlanes

`vp_build_diagram`:

```json
{
  "diagramType": "ActivityDiagram", "name": "Сохранение сценария",
  "elements": [
    {"key": "sw", "type": "ActivitySwimlane2",
     "partitions": [{"key": "u", "name": "Пользователь"}, {"key": "s", "name": "Система"}]},
    {"key": "start", "type": "InitialNode", "parent": "u"},
    {"key": "a1", "type": "ActivityAction", "name": "Ввести данные", "parent": "u"},
    {"key": "a2", "type": "ActivityAction", "name": "Проверить данные", "parent": "s"},
    {"key": "dec", "type": "DecisionNode", "parent": "s"},
    {"key": "a3", "type": "ActivityAction", "name": "Сохранить", "parent": "s"},
    {"key": "end", "type": "ActivityFinalNode", "parent": "s"}
  ],
  "connectors": [
    {"type": "ControlFlow", "from": "start", "to": "a1"},
    {"type": "ControlFlow", "from": "a1", "to": "a2"},
    {"type": "ControlFlow", "from": "a2", "to": "dec"},
    {"type": "ControlFlow", "from": "dec", "to": "a3", "properties": {"guard": "данные верны"}},
    {"type": "ControlFlow", "from": "dec", "to": "a1", "properties": {"guard": "ошибка"}},
    {"type": "ControlFlow", "from": "a3", "to": "end"}
  ]
}
```

Later additions to a lane: `vp_add_shape` with `"parent": "<partition name>"`.

## State machine

```json
{
  "diagramType": "StateDiagram", "name": "Жизненный цикл бота",
  "elements": [
    {"key": "i", "type": "InitialPseudoState"},
    {"key": "draft", "type": "State2", "name": "Черновик"},
    {"key": "pub", "type": "State2", "name": "Опубликован"},
    {"key": "f", "type": "FinalState2"}
  ],
  "connectors": [
    {"type": "Transition2", "from": "i", "to": "draft"},
    {"type": "Transition2", "from": "draft", "to": "pub", "name": "publish"},
    {"type": "Transition2", "from": "pub", "to": "f"}
  ],
  "layout": "hierarchic"
}
```

## ER diagram

```json
{
  "diagramType": "ERDiagram", "name": "Боты",
  "elements": [
    {"key": "bot", "type": "DBTable", "name": "bot", "children": [
      {"type": "DBColumn", "name": "id", "properties": {"type": "integer", "primaryKey": true}},
      {"type": "DBColumn", "name": "name", "properties": {"type": "varchar(255)", "nullable": false}}]},
    {"key": "node", "type": "DBTable", "name": "node", "children": [
      {"type": "DBColumn", "name": "id", "properties": {"type": "integer", "primaryKey": true}}]}
  ],
  "connectors": [{"type": "DBForeignKey", "from": "bot", "to": "node"}]
}
```

The foreign key column is created by VP in the child table.

## Type names

| Diagram | Shapes | Connectors |
| --- | --- | --- |
| Use case | Actor, UseCase, System, Package, NOTE | Association, Include, Extend, Generalization, Dependency |
| Class | Class, Interface, Enumeration, DataType, Package; children Attribute, Operation (Parameter), EnumerationLiteral | Association, Generalization, Realization, Dependency, AssociationClass |
| Activity | InitialNode, ActivityAction, DecisionNode, MergeNode, ForkNode, JoinNode, ActivityFinalNode, FlowFinalNode, ActivityObject, ActivitySwimlane2 | ControlFlow, ObjectFlow |
| State | InitialPseudoState, State2, FinalState2, Choice, Fork, Join | Transition2 |
| ER | DBTable; children DBColumn | DBForeignKey |
| Sequence | use `vp_build_sequence` | |

`vp_list_types` returns the exact list for a diagram.
