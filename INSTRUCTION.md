# ScorePlugins — Полная инструкция по сборке и защите

Дата последнего обновления: **2026-05-10**

---

## ⚡ TL;DR — Быстрая пересборка ВСЕХ плагинов с нуля

Если меняли исходник любого плагина, выполни ЭТИ команды по порядку:

```bash
# 1. Maven build всех изменённых плагинов
cd C:/Users/shoxg/OneDrive/Desktop/plugins/licensed/ScoreLicense    && mvn package -q -DskipTests
cd C:/Users/shoxg/OneDrive/Desktop/plugins/licensed/ScoreAirDrop    && mvn package -q -DskipTests
cd C:/Users/shoxg/OneDrive/Desktop/plugins/licensed/ScoreAutoBuyer  && mvn package -q -DskipTests
cd C:/Users/shoxg/OneDrive/Desktop/plugins/licensed/ScoreHWAuction  && mvn package -q -DskipTests
cd C:/Users/shoxg/OneDrive/Desktop/plugins/licensed/ScorePvP        && mvn package -q -DskipTests
cd C:/Users/shoxg/OneDrive/Desktop/plugins/licensed/ScoreTpa        && mvn package -q -DskipTests

# 2. Re-obfuscate всех 5 плагинов (НЕ ScoreLicense — он берётся из target/*-obf.jar)
cd "C:/Users/shoxg/OneDrive/Desktop/ScorePlugins Build/scripts"
python reobf_all.py

# 3. Pipeline: final6 → final7 → final8 → final8b → final9 → ScorePlugins/
python rename_main.py
python update_hashes.py     # ВАЖНО: обновляет хэши в guard-сорсах из final7/ScoreLicense
python build_v2.py
python fix_helpers.py
python fix_manifest.py
python deliver_v2.py
```

Финальные jar'ы → `C:/Users/shoxg/OneDrive/Desktop/ScorePlugins/`

---

## 0. Обзор защиты

Каждый Score-плагин (ScoreTpa, ScorePvP, ScoreHWAuction, ScoreAutoBuyer, ScoreAirdrop)
оборачивается **guard-обёрткой**. Перед запуском плагина guard проверяет:

1. **SHA-256 cross-jar hash** 5 классов в ScoreLicense.jar:
   - `com/score/license/LicenseDatabase.class`
   - `com/score/license/LicenseStorage.class`
   - `com/score/license/LicenseHeartbeat.class`
   - `com/score/license/LicenseAPI.class`
   - `com/score/license/lIIIll1O0OOIl0IOOl.class` (ранее ScoreLicensePlugin)
2. **Identity check** — `LicenseAPI.identity()` возвращает ожидаемое значение
3. **HMAC-SHA256 challenge** — guard генерирует 32-байтный nonce, ScoreLicense подписывает секретом, guard проверяет совпадение

Если ЛЮБАЯ проверка падает → плагин отключается с сообщением:
`Iltimos haqiqiy ScoreLicense plaginini ornating`

ScoreLicense сам защищён `LicenseGate` (self-hash integrity).

⚠️ **Никаких `LicenseAPI.isLicensed()` в исходниках дочерних плагинов!**
Guard уже всё проверяет. Дубль-проверки в onEnable() ломают classloader при падении ScoreLicense (см. `Известные проблемы → NoClassDefFoundError`).

---

## 1. Структура папки сборки

```
ScorePlugins Build/
├── INSTRUCTION.md                      # эта инструкция
├── INSTRUCTION.old.md                  # предыдущая версия (backup)
├── ScoreLicense-Protection-Guide.md    # доп. описание схемы защиты
├── scripts/                            # Python pipeline скрипты
│   ├── reobf_all.py                    # ★ универсальный reobf 6 jar'ов через Skidfuscator
│   ├── reobf_scoretpa.py               # одиночный reobf ScoreTpa (legacy)
│   ├── reobf_scorepvp.py               # одиночный reobf ScorePvP (legacy)
│   ├── rename_main.py                  # final6 → final7 (rename ScoreLicensePlugin → lIIIll1O0OOIl0IOOl)
│   ├── update_hashes.py                # ★ ОБЯЗАТЕЛЬНО после rename_main: обновляет SHA-256 хэши в guard-сорсах
│   ├── build_v2.py                     # final7 → final8 (компилит guard'ы, инжектит)
│   ├── fix_helpers.py                  # final8 → final8b (добавляет Skidfuscator string-decrypt helpers)
│   ├── fix_manifest.py                 # final8b → final9 (Paper mojang manifest fix)
│   ├── deliver_v2.py                   # final9 → ScorePlugins/ (финальные имена)
│   ├── rebuild_licenseapi.py           # пересборка только LicenseAPI (опционально)
│   └── obfuscate_guards.py             # legacy (не используется в основном flow)
├── templates/
│   └── wrapper_template_v2.java        # шаблон guard-обёртки (синхрон с license-guard/)
├── guard-src/                          # модифицированный LicenseAPI (signChallenge + identity)
│   └── com/score/license/LicenseAPI.java
├── guard-src2/                         # LicenseGate + IntegrityChecker (синхрон с license-guard/src2)
│   └── com/score/license/guard/
├── ScoreLicense/                       # ★ ПОЛНЫЙ исходник ScoreLicense (pom.xml + src)
│   ├── pom.xml
│   └── src/main/...
├── scorelicense-src/                   # старая частичная копия (только LicenseDatabase)
├── cleanup_stale_slots.sql             # ручная очистка таблицы servers
└── config/
    ├── SECRET.hex                      # 32-байтный HMAC секрет (hex)
    ├── SECRET.java.txt                 # тот же секрет в виде long[] M[] / X[]
    └── skid-config.hocon               # Skidfuscator конфигурация
```

### Внешние пути (вне ScorePlugins Build/)

```
C:/Users/shoxg/OneDrive/Desktop/plugins/
├── skidfuscator.jar
├── libs/                               # paper-api-*.jar и зависимости для Skidfuscator
├── licensed/                           # рабочие исходники плагинов
│   ├── ScoreLicense/
│   ├── ScoreAirDrop/
│   ├── ScoreAutoBuyer/
│   ├── ScoreHWAuction/
│   ├── ScorePvP/
│   └── ScoreTpa/
├── license-guard/
│   ├── src2/com/score/license/guard/   # LicenseGate + IntegrityChecker (рабочая копия)
│   └── wrapper_template_v2.java        # шаблон wrapper'а (рабочая копия)
├── obfuscated-final5/                  # вход для obfuscate_guards.py (legacy)
├── obfuscated-final6/                  # ★ результат reobf_all.py — стартовая точка pipeline
├── obfuscated-final7/                  # после rename_main.py
├── obfuscated-final8/                  # после build_v2.py
├── obfuscated-final8b/                 # после fix_helpers.py
└── obfuscated-final9/                  # после fix_manifest.py
```

---

## 2. Требования

- **Java 11+** (JDK для `javac`)
- **Maven 3.6+** (для пересборки плагинов)
- **Python 3.10+**
- **Skidfuscator 2.0.11** в `plugins/skidfuscator.jar`
- **Paper API jar** в `plugins/libs/` (например `paper-api-1.21.1-R0.1-SNAPSHOT.jar`)

---

## 3. Pipeline по шагам

### Шаг A. Сборка плагина из исходника (Maven)

```bash
cd C:/Users/shoxg/OneDrive/Desktop/plugins/licensed/<PLUGIN>
mvn package -q -DskipTests
```

ScoreLicense выполняет: compile → maven-shade-plugin (HikariCP/MySQL → `com.score.license.lib.*`) → ProGuard (renames + keeps).

Остальные 5 плагинов: compile + shade (без ProGuard).

**Output:**
- ScoreLicense → `target/ScoreLicense-1.0.0-obf.jar` (после ProGuard и Skidfuscator из maven)
- Остальные → `target/<Plugin>-1.0.0-shaded.jar` (или `<Plugin>-1.1.0.jar` для ScoreTpa)

### Шаг B. Re-obfuscation через Skidfuscator (`reobf_all.py`)

```bash
cd "C:/Users/shoxg/OneDrive/Desktop/ScorePlugins Build/scripts"
python reobf_all.py
```

Скрипт:
- Берёт ScoreLicense **уже готовый** `target/ScoreLicense-1.0.0-obf.jar` (после ProGuard) и просто **копирует** в final6 (НЕ запускает Skidfuscator повторно — это сломало бы reference obfuscation на больших классах).
- Запускает Skidfuscator с **safe конфигом** (`flowObfuscation=false`) на 5 остальных shaded jar'ах:
  - `flowObfuscation = false` ← КРИТИЧНО, иначе ClassFormatError "Illegal exception table range"
  - `stringEncryption = STANDARD`
  - `numberObfuscation = true`
  - `referenceObfuscation = true`
  - `renamer = enabled`
- Кладёт результаты в `obfuscated-final6/`.

### Шаг C. Pipeline `final6 → ScorePlugins/`

```bash
cd "C:/Users/shoxg/OneDrive/Desktop/ScorePlugins Build/scripts"
python rename_main.py     # final6 → final7
python update_hashes.py   # обновляет хэши в LicenseGate.java + wrapper_template_v2.java
python build_v2.py        # final7 → final8 (компиляция guard'ов с актуальными хэшами + инжект)
python fix_helpers.py     # final8 → final8b
python fix_manifest.py    # final8b → final9
python deliver_v2.py      # final9 → ScorePlugins/
```

**`update_hashes.py`** (новый) автоматически:
1. Читает `obfuscated-final7/licensed-ScoreLicense-1.0.0-obf.jar`.
2. Считает SHA-256 от 5 critical классов.
3. Подставляет хэши в `license-guard/src2/com/score/license/guard/LicenseGate.java` (`EXPECTED.put(...)`) и `license-guard/wrapper_template_v2.java` (`LIC_HASHES`).

Без этого шага guard'ы будут содержать старые хэши и **все плагины упадут** с `Iltimos haqiqiy ScoreLicense plaginini ornating`.

---

## 4. Изменение исходников ScoreLicense

Полный исходник лежит в **`ScorePlugins Build/ScoreLicense/`** — есть `pom.xml` + `src/`. Можно править прямо там, потом скопировать в `plugins/licensed/ScoreLicense/` и пересобрать (или править напрямую в `plugins/licensed/ScoreLicense/` — обе копии надо держать синхронными).

**Если меняли LicenseAPI** (метод `signChallenge` или константы) → хэши автоматически обновятся в `update_hashes.py`. Дополнительный шаг не нужен.

**Если меняли HMAC секрет** (`config/SECRET.hex`) → пересобрать LicenseAPI И wrapper'ы (значения `M[]/X[]` в обоих).

**Если меняли plugin.yml main** (`ScoreLicensePlugin` → другое имя) → меняй `OLD`/`NEW` в `rename_main.py`.

---

## 5. Изменение сообщений

| Сообщение | Файл | Что пересобрать |
|-----------|------|------------------|
| "Pluginlarni ishlatish uchun admindan key oling" | `ScoreLicense/src/main/java/com/score/license/ScoreLicensePlugin.java` (метод `onEnable`) | A → B → C |
| "Iltimos haqiqiy ScoreLicense plaginini ornating" | `license-guard/wrapper_template_v2.java` + `license-guard/src2/.../LicenseGate.java` | C только (rename → update_hashes → build_v2 → ...) |
| Комментарии config.yml | `ScoreLicense/src/main/resources/config.yml` | A → B → C |
| `lang/uz.yml` плагинов | напрямую в финальный jar (см. ниже) | — |

Прямая инжекция в готовый jar:
```python
import zipfile, shutil
src = 'C:/Users/shoxg/OneDrive/Desktop/ScorePlugins/ScorePvP.jar'
tmp = src + '.tmp'
with zipfile.ZipFile(src, 'r') as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    with open('uz.txt', 'rb') as f:
        zout.writestr('lang/uz.yml', f.read())
shutil.move(tmp, src)
```

---

## 6. Известные проблемы и решения (важно — собрано после инцидентов)

### `IncompatibleClassChangeError: ... com.score.license.a.a.c.a.<method>() must be InterfaceMethodref constant`
**Причина**: ProGuard переименовал HikariCP в `a.a.*` — потому что в pom.xml не было `-keep class com.score.license.lib.** { *; }` ИЛИ `<minimizeJar>` был включён ИЛИ старый jar остался в `obfuscated-final6/` после mvn-фикса.

**Решение**:
1. В `pom.xml` секции ProGuard должны быть:
   ```
   <option>-keep class com.score.license.lib.** { *; }</option>
   <option>-keep interface com.score.license.lib.** { *; }</option>
   <option>-keep class org.slf4j.** { *; }</option>
   <option>-dontshrink</option>
   <option>-dontoptimize</option>
   ```
   `<minimizeJar>false</minimizeJar>` в shade-plugin.
2. **Обязательно**: после `mvn package` → `python reobf_all.py` → проверь что `obfuscated-final6/licensed-ScoreLicense-1.0.0-obf.jar` имеет timestamp **сегодняшний** и содержит `com/score/license/lib/hikari/HikariConfig.class`:
   ```bash
   unzip -l obfuscated-final6/licensed-ScoreLicense-1.0.0-obf.jar | grep hikari | head -5
   ```

### `NoClassDefFoundError: com/score/license/LicenseAPI` в дочерних плагинах
**Причина**: исходник дочернего плагина содержал `if (!com.score.license.LicenseAPI.isLicensed()) { ... }` в `onEnable`. Когда ScoreLicense падал, его classloader разрушался → `LicenseAPI.class` становился недоступен → каскадный crash.

**Решение**: УДАЛИТЬ все `LicenseAPI.isLicensed()` проверки из дочерних плагинов. Guard уже всё проверяет на уровне обёртки — дубль не нужен.

Проверка:
```bash
grep -rn "LicenseAPI.isLicensed\|com\.score\.license\.LicenseAPI" \
  C:/Users/shoxg/OneDrive/Desktop/plugins/licensed/Score{AirDrop,AutoBuyer,HWAuction,PvP,Tpa}/src/
```

Должно вернуть **пустой** результат.

### `NPE in onDisable: Cannot invoke "Config.isLoaded(int)" because "<...>" is null`
**Причина**: плагин вызвал `disablePlugin(this)` в `onEnable` ДО того как поле инициализировалось. Bukkit вызвал `onDisable()` → метод обращается к `getiConfig()` → null → NPE.

**Решение**: в `onDisable` всегда null-check. Пример из BAirDrop:
```java
if (getiConfig() == null || !getiConfig().isLoaded()) return;
```

### `IllegalPluginAccessException: Plugin attempted to register task while disabled`
**Причина**: guard создаёт delegate через `Unsafe.allocateInstance` без вызова `setEnabled(true)` → `JavaPlugin.isEnabled = false` → `CraftScheduler.validate()` отклоняет любые задачи и listeners.

**Решение**: в `wrapper_template_v2.java` и `LicenseGate.java` метод `setEnabled(JavaPlugin p, boolean v)` через рефлексию. Вызывается ДО `delegate.onEnable()` и ПОСЛЕ `delegate.onDisable()`. Реализовано.

### `NullPointerException: PluginMeta.getDisplayName()` (Paper)
**Причина**: guard'у не дали PluginMeta при создании delegate.

**Решение**: `copyFields(this, delegate)` копирует ВСЕ поля JavaPlugin (включая private + meta). Реализовано.

### `ClassFormatError: Illegal exception table range` после Skidfuscator
**Причина**: `flowObfuscation = true` ломает порядок exception handlers в крупных try/catch блоках (особенно в `TeleportManager`).

**Решение**: в `reobf_all.py` HOCON config:
```hocon
flowObfuscation { enabled = false }
```

### Skidfuscator OOM (`OutOfMemoryError`)
**Причина**: ScorePvP/HWAuction крупные.

**Решение**: `-Xmx6g` в команде java (уже в `reobf_all.py` для PvP/HWAuction).

### `cannot access ForwardingAudience` при компиляции LicenseGate
**Причина**: `guard.getServer().getPluginCommand(name)` тянет adventure-api на classpath (Server extends ForwardingAudience).

**Решение**: использовать `guard.getCommand(name)` (метод JavaPlugin) — не требует adventure. Уже исправлено.

### `saveResource` IllegalArgumentException
**Причина**: вызов `saveResource(path, false)` для отсутствующего ресурса.

**Решение**: helper-метод:
```java
private void saveResourceIfAbsent(String path) {
    if (new File(getDataFolder(), path).exists()) return;
    try { saveResource(path, false); }
    catch (IllegalArgumentException ignored) {}
}
```

### Команды плагина не работают (выполнились на guard, не на delegate)
**Причина**: `PluginCommand.owningPlugin` указывает на guard, не на delegate.

**Решение**: `rebindCommands(this, delegate)` через рефлексию устанавливает `owningPlugin = delegate` ДО `delegate.onEnable()`. Реализовано в обоих guard'ах.

### ScoreLicense получился 10MB+ после reobf
**Причина**: запустили Skidfuscator поверх **`-shaded.jar`** (без ProGuard) — обфусцированы все 4MB shaded HikariCP вместе с пустыми helper-классами.

**Решение**: для ScoreLicense **НЕ ЗАПУСКАТЬ Skidfuscator повторно**. Просто копируй `target/ScoreLicense-1.0.0-obf.jar` (уже после ProGuard и встроенного Skidfuscator) в `obfuscated-final6/licensed-ScoreLicense-1.0.0-obf.jar`. Это уже делается в `reobf_all.py`.

### `NoSuchMethodError: CAirDrop.<init>(FileConfiguration, File, int)` (ScoreAirdrop)
**Причина**: Skidfuscator пассы `numberObfuscation` и `referenceObfuscation` ломают дескриптор конструктора в call-site (`new CAirDrop(...)`), добавляя phantom `int` параметр, но не меняют сам ctor — получается рассинхрон.

**Решение**: в `reobf_all.py` ОБЯЗАТЕЛЬНО держать `numberObfuscation = false` и `referenceObfuscation = false`. Проверка после reobf:
```bash
cd /tmp && unzip -o "C:/Users/shoxg/OneDrive/Desktop/plugins/obfuscated-final6/ScoreAirdrop-1.0.0-obf.jar" "org/by1337/bairdrop/CAirDrop.class" "org/by1337/bairdrop/BAirDrop.class"
javap -p org/by1337/bairdrop/CAirDrop.class | grep "CAirDrop("
javap -p -c org/by1337/bairdrop/BAirDrop.class | grep -o 'CAirDrop\."<init>":([^)]*)'
# Сигнатуры ctor и call-site ДОЛЖНЫ совпадать
```

### `IncompatibleClassChangeError: ... LegacyComponentSerializer.legacyAmpersand() must be InterfaceMethodref constant`
**Причина**: исходник скомпилирован против paper-api 1.21.1, где `LegacyComponentSerializer` был классом со статическим методом → bytecode emits `invokestatic`. На сервере Paper 1.21.11 этот тип стал интерфейсом → JVM ругается.

**Решение**: 
1. В `plugins/libs/` должен быть **paper-api 1.21.11** (скачать с `https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/`).
2. В pom.xml каждого плагина, использующего adventure-types, dependency должна быть **paper-api 1.21.11-R0.1-SNAPSHOT** (а не purpur-api 1.21.1).
3. После замены — `mvn -q package` для пересборки.

### Старый JAR в `target/` подхвачен вместо свежего (ScoreTpa)
**Причина**: `mvn package` создаёт `ScoreTpa-1.1.0.jar`, но в `target/` ещё валяется `ScoreTpa License.jar` от 13 апреля с `LicenseAPI.isLicensed()`. Если `reobf_all.py` указывает на старый файл — старый код пройдёт через всю pipeline.

**Решение**: в `reobf_all.py` JOBS-таблица должна указывать на **свежий** mvn output (`ScoreTpa-1.1.0.jar`, НЕ `ScoreTpa License.jar`). При смене версии плагина — обновить путь.

---

## 7. Контрольные проверки (sanity checks)

После полного pipeline проверь:

```bash
# 1. ScoreLicense имеет правильные пути HikariCP
unzip -l C:/Users/shoxg/OneDrive/Desktop/ScorePlugins/ScoreLicense.jar | grep hikari | head -3
# Должно: com/score/license/lib/hikari/HikariConfig.class и т.д.
# НЕ должно: com/score/license/a/a/...

# 2. ScoreLicense имеет переименованный main
unzip -l C:/Users/shoxg/OneDrive/Desktop/ScorePlugins/ScoreLicense.jar | grep lIIIll
# Должно: com/score/license/lIIIll1O0OOIl0IOOl.class

# 3. plugin.yml main указывает на новое имя
unzip -p C:/Users/shoxg/OneDrive/Desktop/ScorePlugins/ScoreLicense.jar plugin.yml | grep main
# main: com.score.license.lIIIll1O0OOIl0IOOl

# 4. Дочерние плагины имеют LicenseGate-style guard
unzip -l C:/Users/shoxg/OneDrive/Desktop/ScorePlugins/ScorePvP.jar | grep -i guard

# 5. Хэши совпадают между guard и ScoreLicense
python -c "
import zipfile, hashlib, re
lic = 'C:/Users/shoxg/OneDrive/Desktop/ScorePlugins/ScoreLicense.jar'
classes = ['com/score/license/LicenseDatabase.class','com/score/license/LicenseStorage.class',
           'com/score/license/LicenseHeartbeat.class','com/score/license/LicenseAPI.class',
           'com/score/license/lIIIll1O0OOIl0IOOl.class']
with zipfile.ZipFile(lic) as z:
    for c in classes: print(c, hashlib.sha256(z.read(c)).hexdigest())
"
# Хэши должны совпадать с теми что встроены в guard любого дочернего плагина.
```

---

## 8. Добавление НОВОГО плагина под защиту

1. Создай Maven-проект, ничего НЕ импортируй из `com.score.license` в исходниках.
2. В `pom.xml` сделай shade в `target/<Plugin>-<ver>-shaded.jar`.
3. Добавь в `reobf_all.py` секцию `JOBS`:
   ```python
   ("tmp-skid-myplugin",
    "licensed/MyPlugin/target/MyPlugin-1.0.0-shaded.jar",
    "licensed-MyPlugin-1.0.0-obf.jar",
    "4g"),
   ```
4. Добавь в `build_v2.py` карту `PLUGINS`:
   ```python
   "MyPlugin": ("licensed-MyPlugin-1.0.0-obf.jar", "com.myplugin.guard.MyGuard"),
   ```
5. В `deliver_v2.py` соответственно — secure имя.
6. Прогоняй полный pipeline.

⚠️ **Никогда не пиши** `LicenseAPI.isLicensed()` в исходнике нового плагина — guard wrapper всё сделает.

---

## 9. Полная история изменений

### 2026-05-11 — исправление ошибок Paper 1.21.11 / Skidfuscator
- **paper-api 1.21.1 → 1.21.11**: скачана новая версия в `plugins/libs/paper-api-1.21.11-R0.1-SNAPSHOT.jar`, старая перенесена в `plugins/libs-archive/`. Обновлены пути в `build_v2.py`, `obfuscate_guards.py`, `license-guard/{build_v2,obfuscate_guards,build_guard}.py`.
- **ScorePvP pom.xml**: dependency `purpur-api 1.21.1` → `paper-api 1.21.11` (purpur snapshot репозиторий вернул 404). Исправляет `IncompatibleClassChangeError: LegacyComponentSerializer.legacyAmpersand() must be InterfaceMethodref constant` — теперь bytecode emits `invokeinterface` корректно.
- **reobf_all.py**: `numberObfuscation = false`, `referenceObfuscation = false` — Skidfuscator с этими пассами портил конструктор `org.by1337.bairdrop.CAirDrop` (добавлял phantom `int` параметр на call-site но не на сам ctor), что давало `NoSuchMethodError: CAirDrop.<init>(FileConfiguration,File,int)` в рантайме. С отключёнными пассами Skidfuscator всё ещё меняет дескриптор, НО консистентно (и ctor и call-site становятся 3-arg), и плагин работает.
- **reobf_all.py ScoreTpa**: путь источника `licensed/ScoreTpa/target/ScoreTpa License.jar` (от 13 апреля, древний с `LicenseAPI.isLicensed()`) → `licensed/ScoreTpa/target/ScoreTpa-1.1.0.jar` (свежий после `mvn package`).

### 2026-05-10 — финальная стабилизация после каскада ошибок
- Удалены все `LicenseAPI.isLicensed()` из исходников 5 дочерних плагинов (ScoreAirDrop, ScoreAutoBuyer, ScoreHWAuction, ScorePvP, ScoreTpa) — устраняет `NoClassDefFoundError` каскад.
- Добавлен null-check в `BAirDrop.onDisable()` — устраняет NPE при early-disable.
- ScoreLicense: переход на `target/ScoreLicense-1.0.0-obf.jar` (уже после ProGuard) вместо повторного Skidfuscator поверх shaded — jar 2.7MB вместо 10.9MB.
- Создан `reobf_all.py` — единая точка для re-обфускации всех 6 jar'ов.
- Создан `update_hashes.py` (см. этот же пакет) — авто-обновление SHA-256 хэшей в guard-сорсах.
- Скопирован полный исходник ScoreLicense в `ScorePlugins Build/ScoreLicense/`.
- Обновлены хэши:
  - LicenseDatabase: `1dc2e4241f9c80a94a4cc8d3e2caad89f2ec7e02b104ec389634c6485cc4c2c3`
  - LicenseStorage:  `39af05e903c6617bc0f786eed454d12aa7daa03a1e5063c37674f4ae57dd1267`
  - LicenseHeartbeat:`ba69a074085d37052f9a4fd9aef3cc5c06f1f3a6a502889f2b9d5941936cf348`
  - LicenseAPI:      `be80d46863305646ab92e7938bcf6909133664d339367822fda2fa5774a70b48`
  - lIIIll1O0OOIl0IOOl: `b7ba38ba15f7fda4b21288985e6ac1a3b39458244ab883dcd2013e04630b7441`

### 2026-05-09 — fixes for ClassFormatError + saveResource + OOM
- Skidfuscator config: `flowObfuscation = false` для решения ClassFormatError в TeleportManager.
- ScorePvP.java: `saveResourceIfAbsent` helper.
- reobf_scorepvp.py: `-Xmx6g` против OOM.
- LicenseGate.java: `getCommand` вместо `getServer().getPluginCommand` (избежать adventure-api dep).

### 2026-05-08 — guard infrastructure
- `setEnabled` reflection helper в обоих guard'ах.
- `rebindCommands` для исправления `PluginCommand.owningPlugin`.
- `Unsafe.allocateInstance` для delegate.
- Cross-jar SHA-256 + HMAC challenge.

---

## 10. Контакты / справка

Все pipeline скрипты — в `ScorePlugins Build/scripts/`. Каждый имеет docstring в начале.

Если pipeline сломался посреди — смотри последний шаг по timestamp в `obfuscated-final*/`. Каждый этап перезапускаем независимо (вход — предыдущая папка).
