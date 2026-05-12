# ScoreLicense + 5 защищённых плагинов

Репозиторий для сборки `ScoreLicense.jar` и 5 зависимых плагинов
(`ScoreAirdrop`, `ScoreAutoBuyer`, `ScoreHWAuction`, `ScorePvP`, `ScoreTpa`)
с обфускацией и cross-jar лицензионной защитой.

## Быстрый старт в GitHub Codespace

См. `BUILD_IN_CODESPACE.md` — там полный гайд по пересборке в Linux/Codespace
с шагами, командами и списком известных проблем.

```bash
# Установка зависимостей (Ubuntu codespace)
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk maven python3 unzip

# Сборка
for p in ScoreLicense ScoreAirDrop ScoreAutoBuyer ScoreHWAuction ScorePvP ScoreTpa; do
  (cd "licensed/$p" && mvn -q -DskipTests package)
done

python3 scripts/reobf_all.py
python3 scripts/rename_main.py
python3 scripts/update_hashes.py
python3 scripts/build_v2.py
python3 scripts/fix_helpers.py
python3 scripts/fix_manifest.py
python3 scripts/deliver_v2.py
```

Финальные jar'ы окажутся в `dist/`.

## Структура

| Путь | Описание |
|---|---|
| `licensed/` | Исходники 6 плагинов (ScoreLicense + 5 зависимых) |
| `license-guard/` | Guard-обёртки (cross-jar SHA-256 + HMAC challenge) |
| `scripts/` | Python pipeline (reobf, rename, hash-update, build, deliver) |
| `templates/` | wrapper_template_v2.java для build_v2.py |
| `guard-src2/` | Копия guard-сорсов |
| `libs/` | Provided jar'ы для Skidfuscator (paper-api 1.21.11, HikariCP, и т.д.) |
| `skidfuscator.jar` | 15MB обфускатор (нет в Maven Central) |
| `logs/` | Серверные логи с ошибками (latest-6.log, latest-7.log) |
| `INSTRUCTION.md` | Подробная история и debug-инструкция |
| `BUILD_IN_CODESPACE.md` | Гайд по сборке в codespace |

## Защита

Каждый из 5 зависимых плагинов имеет guard-обёртку, которая:

1. Проверяет SHA-256 5 критичных классов в `ScoreLicense.jar`
2. Проверяет `LicenseAPI.identity()` возвращает ожидаемый хэш
3. Делает HMAC-SHA256 challenge с 32-байтным nonce
4. Только при прохождении всех 3 проверок запускает реальный main-класс
   через `Unsafe.allocateInstance`

Если `ScoreLicense` поддельный или отсутствует — guard отключает плагин
с сообщением `Iltimos haqiqiy ScoreLicense plaginini ornating`.

## Известные проблемы

Полный список — в `BUILD_IN_CODESPACE.md` (раздел 3). Кратко:

- **A** — не вызывай `LicenseAPI.isLicensed()` в исходниках 5 зависимых плагинов (caascade NPE на onDisable)
- **B** — `IllegalPluginAccessException: register task while disabled` (paper 1.21.11 строже)
- **C** — `Iltimos haqiqiy ScoreLicense plaginini ornating` → запусти `update_hashes.py` перед `build_v2.py`
- **D** — `NoSuchMethodError CAirDrop.<init>(..., int)` → отключи `numberObfuscation` и `referenceObfuscation`
- **E** — `IncompatibleClassChangeError LegacyComponentSerializer` → используй paper-api 1.21.11, не 1.21.1
- **F** — ScoreLicense раздулся до 10MB → не запускай Skidfuscator поверх pom-output, просто копируй
- **G** — старый jar в `target/` подхватывается вместо свежего → проверь имена в `reobf_all.py`

## Что НЕ делать

- ❌ Не вызывай `LicenseAPI.isLicensed()` в исходниках зависимых плагинов
- ❌ Не включай `numberObfuscation` / `referenceObfuscation` в Skidfuscator
- ❌ Не запускай Skidfuscator над `ScoreLicense` (уже обфусцирован через pom)
- ❌ Не используй paper-api 1.21.1 — только 1.21.11
- ❌ Не пуш в `main` без sanity-проверок (см. `BUILD_IN_CODESPACE.md` раздел 5)

---

**Последний рабочий билд**: 2026-05-10 18:52
**Дата актуализации**: 2026-05-12
