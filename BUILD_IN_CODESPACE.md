# Сборка ScoreLicense + 5 плагинов в GitHub Codespace

Эта инструкция — для пересборки всех плагинов в **GitHub Codespace** (Linux, Ubuntu).
На локальной Windows-машине всё уже работало (см. `INSTRUCTION.md`), но повторение
сборки выматывает — в codespace будем делать всё одним скриптом.

## Цель
Получить готовые obfuscated jar'ы в `dist/`:
- `ScoreLicense.jar` — главный плагин с лицензией
- `ScoreAirdrop.jar`, `ScoreAutoBuyer.jar`, `ScoreHWAuction.jar`, `ScorePvP.jar`, `ScoreTpa.jar` — 5 зависимых плагинов

Каждый из 5 зависимых плагинов имеет **guard-обёртку**, которая:
1. Проверяет SHA-256 5 критичных классов в установленном `ScoreLicense.jar`
2. Проверяет `LicenseAPI.identity()` возвращает ожидаемый хэш
3. Делает HMAC-SHA256 challenge с 32-байтным nonce
4. Только если ВСЕ 3 проверки пройдены — запускает реальный main-класс плагина через `Unsafe.allocateInstance`

Если ScoreLicense поддельный или не активен — guard отключает плагин с сообщением
`Iltimos haqiqiy ScoreLicense plaginini ornating`.

## 1. Установка зависимостей (одноразово в codespace)

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk maven python3 unzip
java -version   # должен быть 21+
mvn -version    # 3.6+
python3 --version
```

## 2. Структура репозитория

```
.
├── licensed/                    # исходники 6 плагинов (ScoreLicense + 5 зависимых)
│   ├── ScoreLicense/            # главный плагин (ProGuard inside pom)
│   ├── ScoreAirDrop/
│   ├── ScoreAutoBuyer/
│   ├── ScoreHWAuction/
│   ├── ScorePvP/
│   └── ScoreTpa/
├── license-guard/               # guard wrapper sources + старая build-инфраструктура
│   ├── src2/com/score/license/guard/
│   │   ├── IntegrityChecker.java
│   │   └── LicenseGate.java    # self-hash integrity для ScoreLicense
│   └── wrapper_template_v2.java # cross-jar hash + HMAC для 5 зависимых
├── scripts/                     # pipeline-скрипты (Python)
│   ├── reobf_all.py             # обфускация всех 6 jar'ов через Skidfuscator
│   ├── rename_main.py           # ScoreLicensePlugin -> lIIIll1O0OOIl0IOOl
│   ├── update_hashes.py         # авто-обновление SHA-256 в guard-сорсах
│   ├── build_v2.py              # компиляция guard'ов + впайка в jar'ы
│   ├── fix_helpers.py           # удаление случайно попавших helper-классов
│   ├── fix_manifest.py          # restore Multi-Release: true и т.п.
│   └── deliver_v2.py            # копирование финальных jar'ов в dist/
├── templates/                   # копии wrapper_template_v2.java для build_v2.py
├── guard-src2/                  # копия license-guard/src2 (для build_v2.py)
├── libs/                        # все провайдные jar'ы для Skidfuscator class-resolution
├── skidfuscator.jar             # 15MB — обфускатор (нет в Maven Central)
├── logs/                        # последние server-логи (latest-6.log, latest-7.log)
├── INSTRUCTION.md               # подробная история и detailed-инструкция
└── BUILD_IN_CODESPACE.md        # этот файл
```

## 3. Известные проблемы (из логов)

Перед сборкой ОБЯЗАТЕЛЬНО прочитай это, иначе наступишь на старые грабли:

### Проблема A — `License not active. Plugin disabled` каскад (log-7)
**Симптом**: ScoreLicense говорит "admindan key oling", а 5 зависимых плагинов
сами себя disable'ят и ScoreAirdrop падает с NPE на onDisable.

**Причина**: в исходниках 5 плагинов вызывается `LicenseAPI.isLicensed()` в onEnable.
Если ScoreLicense не активен, classloader плагина теряет ссылку на LicenseAPI,
и onDisable падает с NoClassDefFoundError → каскад.

**Решение**: УБРАТЬ все `LicenseAPI.isLicensed()` из исходников 5 зависимых плагинов.
**Проверка делается ТОЛЬКО guard-обёрткой** (cross-jar SHA-256 + HMAC challenge).
Guard сам отключает плагин с правильным сообщением, без NPE.

```bash
grep -rn "isLicensed\|LicenseAPI" licensed/Score{AirDrop,AutoBuyer,HWAuction,PvP,Tpa}/src/main/java/
# должно ничего не найти
```

### Проблема B — `IllegalPluginAccessException: Plugin attempted to register task while disabled` (log-6)
**Симптом**: ScoreLicense.onEnable() вызывает `runTaskTimerAsynchronously`, но плагин помечен disabled.

**Причина**: guard (`LicenseGate`) использует `setEnabled(delegate, true)` reflection-хак,
но Paper 1.21.11 проверяет `JavaPlugin.isEnabled` через scheduler.validate() более строго.

**Решение**: в `wrapper_template_v2.java` и `LicenseGate.java` после `setEnabled(delegate, true)`
дополнительно прописывать `isEnabled` через PluginManager API. Если всё ещё не работает —
позже зарегистрируем задачу через `Bukkit.getScheduler().runTaskLater(this, () -> delegate.scheduleStuff(), 1L)`.

### Проблема C — `Iltimos haqiqiy ScoreLicense plaginini ornating` (log-6, ScorePvP)
**Симптом**: guard говорит "поставь настоящий ScoreLicense" хотя он стоит.

**Причина**: хэши в guard-исходниках устарели — после нового обфусцирования ScoreLicense
байты классов изменились, SHA-256 не сходится.

**Решение**: `python3 scripts/update_hashes.py` ПОСЛЕ `rename_main.py` и ДО `build_v2.py`.

### Проблема D — `NoSuchMethodError: CAirDrop.<init>(FileConfiguration, File, int)` (ранее)
**Симптом**: ScoreAirdrop падает на старте, сервер ругается на конструктор.

**Причина**: Skidfuscator с включёнными `numberObfuscation` или `referenceObfuscation`
ломает дескриптор `org.by1337.bairdrop.CAirDrop` — добавляет phantom `int` в call-site
но не в сам ctor.

**Решение**: в `scripts/reobf_all.py` уже стоит `numberObfuscation = false` и
`referenceObfuscation = false`. НЕ МЕНЯЙ.

### Проблема E — `IncompatibleClassChangeError: LegacyComponentSerializer.legacyAmpersand() must be InterfaceMethodref constant`
**Симптом**: ScorePvP падает с этой ошибкой на сервере Paper 1.21.11.

**Причина**: исходник скомпилирован против paper-api 1.21.1, где `LegacyComponentSerializer`
был классом со статическим методом. В 1.21.11 это интерфейс.

**Решение**: в `licensed/ScorePvP/pom.xml` уже стоит `paper-api 1.21.11-R0.1-SNAPSHOT`
(вместо `purpur-api 1.21.1`). В `libs/` лежит свежий `paper-api-1.21.11-R0.1-SNAPSHOT.jar`.

### Проблема F — ScoreLicense получился 10MB+ после reobf
**Причина**: запустили Skidfuscator поверх shaded.jar (4MB HikariCP внутри).

**Решение**: ScoreLicense НЕ обфусцируется через `reobf_all.py`. Его `pom.xml`
сам запускает ProGuard + Skidfuscator и пишет в `target/ScoreLicense-1.0.0-obf.jar` (2.7MB).
`reobf_all.py` просто копирует этот файл.

### Проблема G — старый jar в target/ подхвачен вместо свежего (ScoreTpa)
**Симптом**: после правки исходника изменения не появляются в финальном jar.

**Причина**: `mvn package` создаёт `ScoreTpa-1.1.0.jar`, но в `target/` также есть
старый `ScoreTpa License.jar` от 13 апреля.

**Решение**: в `scripts/reobf_all.py` пути указывают на **свежий** mvn output. Если
меняли версию плагина — обнови путь.

## 4. Команды для полной пересборки

```bash
# 1. Maven build 6 плагинов
for p in ScoreLicense ScoreAirDrop ScoreAutoBuyer ScoreHWAuction ScorePvP ScoreTpa; do
  echo "=== $p ==="
  (cd "licensed/$p" && mvn -q -DskipTests package)
done

# 2. Re-obfuscation (5 jar'ов через Skidfuscator + ScoreLicense копируется)
python3 scripts/reobf_all.py

# 3. Pipeline: final6 → final7 → final8 → final8b → final9 → dist/
python3 scripts/rename_main.py
python3 scripts/update_hashes.py     # ВАЖНО: обновляет хэши в guard-сорсах
python3 scripts/build_v2.py
python3 scripts/fix_helpers.py
python3 scripts/fix_manifest.py
python3 scripts/deliver_v2.py
```

Финальные jar'ы будут в `dist/` (раньше было `C:/Users/shoxg/OneDrive/Desktop/ScorePlugins/`).

## 5. Проверка sanity после сборки

```bash
# 1. ScoreLicense имеет правильные пути HikariCP
unzip -l dist/ScoreLicense.jar | grep -i hikari | head -3
# должно: com/score/license/lib/hikari/HikariConfig.class

# 2. main: в plugin.yml — переименованный
unzip -p dist/ScoreLicense.jar plugin.yml | grep main
# должно: main: com.score.license.lIIIll1O0OOIl0IOOl

# 3. 5 зависимых имеют guard-классы внутри
for f in dist/Score{Airdrop,AutoBuyer,HWAuction,PvP,Tpa}.jar; do
  echo "=== $f ==="
  unzip -l "$f" | grep -c "guard/"
done
# каждый > 0

# 4. В исходниках 5 зависимых НЕТ isLicensed()
grep -rn "LicenseAPI\.isLicensed\|isLicensed()" licensed/Score{AirDrop,AutoBuyer,HWAuction,PvP,Tpa}/src/main/java/
# должно: ничего

# 5. В обфусцированных jar'ах НЕТ isLicensed
for f in dist/Score{Airdrop,AutoBuyer,HWAuction,PvP,Tpa}.jar; do
  echo "=== $f ==="
  unzip -p "$f" "*.class" 2>/dev/null | strings | grep -c "isLicensed" || true
done
# каждый = 0
```

## 6. Что делать если pipeline сломался

Каждый этап пишет в отдельную папку (`obfuscated-final6/` → `final7/` → ...). Если
сломался посреди — смотри последний timestamp в `obfuscated-final*/` и запускай шаг
с этой папки. Каждый скрипт независим: вход — предыдущая папка, выход — следующая.

Если хэши не сходятся (Проблема C) — `python3 scripts/update_hashes.py` и повторно
`build_v2.py`. update_hashes сам читает SHA-256 из `final7/ScoreLicense.jar` и
впаивает в guard-сорсы.

## 7. Что НЕ делать

- ❌ Не добавляй `LicenseAPI.isLicensed()` в исходники зависимых плагинов
- ❌ Не включай `numberObfuscation` или `referenceObfuscation` в Skidfuscator config
- ❌ Не запускай Skidfuscator над ScoreLicense (он уже обфусцирован через pom)
- ❌ Не используй paper-api 1.21.1 — только 1.21.11
- ❌ Не пуш в main без проверок sanity (раздел 5)

## 8. Связанные файлы

- `INSTRUCTION.md` — полная история, детальные шаги, debug-команды
- `logs/latest-6.log`, `logs/latest-7.log` — server-логи с ошибками которые мы чиним
- `licensed/ScoreLicense/src/main/java/com/score/license/LicenseAPI.java` — API лицензии
- `license-guard/wrapper_template_v2.java` — guard-шаблон с cross-jar hash + HMAC

---

**Дата**: 2026-05-12. Последний рабочий билд — 2026-05-10 18:52.
