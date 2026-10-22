# ScoreLicense + 5 защищённых плагинов

Сборка `ScoreLicense.jar` и 5 зависимых плагинов
(`ScoreAirdrop`, `ScoreAutoBuyer`, `ScoreHWAuction`, `ScorePvP`, `ScoreTpa`)
для Paper 1.21.11 с обфускацией и cross-jar лицензионной защитой.

## Структура

```text
.plugins/          Исходники плагинов (src + pom.xml для каждого)
  ScoreLicense/
  ScoreAirDrop/
  ScoreAutoBuyer/
  ScoreHWAuction/
  ScorePvP/
  ScoreTpa/
  FakeScoreLicense/   Поддельный ScoreLicense для проверки guard'ов

license-guard/     Guard wrapper (cross-jar SHA-256 + HMAC challenge)
  src2/com/score/license/guard/    Сорцы LicenseGate + IntegrityChecker
  wrapper_template_v2.java         Шаблон wrapper'а для зависимых плагинов    

libs/              Provided jar'ы (paper-api 1.21.11, HikariCP и т.д.)
scripts/           Pipeline (Python)
skidfuscator.jar   Обфускатор (нет в Maven Central)   

dist/              Готовые jar'ы
  licensed/        6 jar'ов с лицензионной защитой
  no-licensed/     5 jar'ов БЕЗ лицензии (обфусцированы, но без guard)
```

## Сборка

```bash
# 1) Maven build для каждого плагина
for p in ScoreLicense ScoreAirDrop ScoreAutoBuyer ScoreHWAuction ScorePvP ScoreTpa; do
  (cd ".plugins/$p" && mvn -q -DskipTests package)
done

# 2) Pipeline (порядок строгий)
python3 scripts/reobf_all.py        # Skidfuscator -> obfuscated-final6/
python3 scripts/rename_main.py      # ScoreLicensePlugin -> lIIIll1O0OOIl0IOOl
python3 scripts/update_hashes.py    # пропатчить SHA-256 в guard-сорцах
python3 scripts/build_v2.py         # инжект guard'а в зависимые jar'ы
python3 scripts/fix_helpers.py      # +helper-классы
python3 scripts/fix_manifest.py     # пропатчить META-INF/MANIFEST.MF
python3 scripts/build_nolic.py      # собрать NoLic-варианты
python3 scripts/deliver_v2.py       # положить итог в dist/licensed и dist/no-licensed
```

Итог:

- `dist/licensed/` — 6 jar'ов с лицензионной защитой (`ScoreLicense.jar`,
  `ScoreAirdrop.jar`, `ScoreAutoBuyer.jar`, `ScoreHWAuction.jar`,
  `ScorePvP.jar`, `ScoreTpa.jar`).
- `dist/no-licensed/` — 5 jar'ов без guard'а (`*-NoLic.jar`), обфусцированы,
  но не требуют `ScoreLicense`.

## Защита

`licensed/`-вариант каждого зависимого плагина обёрнут в guard, который:

1. Проверяет SHA-256 5 критичных классов в `ScoreLicense.jar`
2. Проверяет `LicenseAPI.identity()` возвращает ожидаемый хэш
3. Делает HMAC-SHA256 challenge с 32-байтным nonce
4. Опрашивает `LicenseAPI.isLicensed()` периодически в рантайме
5. Только при успехе всех проверок запускает реальный main через
   `Unsafe.allocateInstance`

При проваленной проверке guard пишет в консоль:
`ScoreLicense plagini aktiv emas, plagin ochirilmoqda.` и отключает плагин.

`no-licensed/`-вариант — те же jar'ы после `rename_main.py`, но без
guard-инжекции (`build_nolic.py` копирует их из `obfuscated-final7/`).

## Особенности билда

- **ScoreLicense**: НЕ запускать Skidfuscator поверх pom-output (раздуется до
  10MB). `reobf_all.py` копирует готовый `*-obf.jar` напрямую.
- **ScorePvP / ScoreAirdrop / ScoreHWAuction**: пропускают Skidfuscator
  (SSVM крашится на Java 21+; method-rewriting ломает интерфейсы;
  HWAuction вешается на 17MB). Guard всё равно инжектится в `build_v2.py`.
- **ScoreTpa**: `TeleportManager` исключён из Skidfuscator
  (ClassFormatError на exception table).
- **paper-api**: только `1.21.11-R0.1-SNAPSHOT`, не `1.21.1` (иначе
  `IncompatibleClassChangeError` на `LegacyComponentSerializer`).
- **`update_hashes.py` обязателен** между `rename_main.py` и `build_v2.py` —
  иначе guard содержит протухшие SHA и все плагины упадут с
  `ScoreLicense plagini aktiv emas`.

## Чего НЕ делать

- Не вызывать `LicenseAPI.isLicensed()` напрямую в исходниках зависимых
  плагинов (cascade NPE на onDisable). Проверка делается guard'ом.
- Не включать `numberObfuscation` / `referenceObfuscation` в Skidfuscator
  (`NoSuchMethodError` на интерфейсных методах).
- Не запускать Skidfuscator на `ScoreLicense` (см. выше).
- Не пушить в `main` без проверки `dist/licensed/` на сервере.
