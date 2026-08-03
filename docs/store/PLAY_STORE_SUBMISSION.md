# Google Play production checklist: V Slot

Статус документа: черновик для релизного кандидата, проверен по коду 2026-07-28; официальные требования Google Play повторно сверены 2026-08-03. Это технический checklist, а не юридическое заключение. Требования магазина и законодательство России нужно повторно проверить непосредственно перед отправкой.

## 1. Зафиксированная область продукта

- Package: `com.vslot.app`.
- Текущая версия в коде: `versionName 1.0.0`, `versionCode 1`.
- Android: `minSdk 26`, `targetSdk 36`.
- Тип: бесплатная игра, social casino / simulated slots.
- Страна распространения production: только Россия (`RU`).
- Аудитория продукта: только 18+; приложение не предназначено для детей.
- Только виртуальные монеты без реальной стоимости. Нет депозитов, вывода, денежных или материальных призов, NFT, купонов, передачи валюты между игроками и ссылок на real-money casino.
- Нет рекламы, покупок, подписок, аккаунтов, авторизации и пользовательского контента.
- Аналитика AppMetrica включается только после отдельного opt-in. Push через Firebase Cloud Messaging и AppMetrica включается только после явного действия пользователя и системного разрешения.

Любое изменение этих фактов требует повторной проверки Play Console, Data safety, privacy policy, возрастных ограничений и этого документа.

## 2. Блокеры до создания production-релиза

- [ ] Указать настоящий публичный `V_SLOT_PRIVACY_POLICY_URL`. Страница должна быть HTTPS, без геоблокировки, не PDF, доступна без входа и явно называть V Slot и юридическое лицо/разработчика из store listing. Google требует privacy policy в Play Console и внутри приложения; она должна описывать сбор, использование, передачу, безопасность, сроки хранения и удаление данных: [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/17105854?hl=en#privacy_policy).
- [ ] Указать настоящий `V_SLOT_APPMETRICA_API_KEY`, закрепить его через `V_SLOT_APPMETRICA_API_KEY_SHA256` и принять актуальные [AppMetrica Terms](https://yandex.com/legal/metrica_termsofuse/en/) и применимый [DPA](https://yandex.com/legal/metrica_agreement/en/). Не использовать тестовый проект для production-трафика.
- [ ] Добавить настоящий `app/src/release/google-services.json` с Android-клиентом для `com.vslot.app`, затем закрепить ожидаемые `project_id` и `mobilesdk_app_id` через `V_SLOT_FIREBASE_PROJECT_ID` и `V_SLOT_FIREBASE_APP_ID`. Проверить отдельный production Firebase project, права доступа и владельцев; не переиспользовать его в debug/QA.
- [ ] Настроить release keystore через `V_SLOT_RELEASE_STORE_FILE`, `V_SLOT_RELEASE_KEY_ALIAS` и ожидаемый `V_SLOT_RELEASE_CERT_SHA256`. Пароли `V_SLOT_RELEASE_STORE_PASSWORD` и `V_SLOT_RELEASE_KEY_PASSWORD` передавать только через environment из secret storage/CI, с резервной копией и документированным доступом.
- [ ] Назначить владельца privacy/support обращений, передать реальный email через `V_SLOT_SUPPORT_EMAIL` и точное публичное юридическое имя через `V_SLOT_DEVELOPER_LEGAL_NAME`. Не подставлять значения-заглушки.
- [ ] Закрыть все пункты `Не отправлять` из [DATA_SAFETY_DRAFT.md](DATA_SAFETY_DRAFT.md), затем заполнить и независимо проверить [PRIVACY_POLICY_RU_TEMPLATE.xhtml](PRIVACY_POLICY_RU_TEMPLATE.xhtml). Snapshot опубликованной политики должен быть самодостаточным валидным XHTML на русском языке, содержать точные `V_SLOT_DEVELOPER_LEGAL_NAME`, `V_SLOT_SUPPORT_EMAIL`, `com.vslot.app`, canonical `V_SLOT_PRIVACY_POLICY_URL` и размеченные разделы `operator`, `scope`, `local-data`, `analytics`, `push`, `processors`, `retention`, `deletion`, `security`, `children`, `changes`, `contact`; скрипты, формы, iframe, внешние ресурсы и placeholders запрещены. Заполнить [DATA_SAFETY_EVIDENCE_TEMPLATE.json](DATA_SAFETY_EVIDENCE_TEMPLATE.json), привязать его к release commit и хешам raw capture/Play Console export/privacy snapshot/внутреннего архива. Сохранить JSON и внешний raw-evidence ZIP в protected CI и закрепить оба SHA-256. Только после этого задать env-only `V_SLOT_DATA_SAFETY_REVIEWED_VERSION_CODE`, равный текущему `versionCode`; старое, отсутствующее или не совпадающее evidence блокирует `verifyStoreReadiness` и `verifyStoreRelease`.
- [ ] Проверить реестр [`ASSET_PROVENANCE_INVENTORY.json`](../legal/ASSET_PROVENANCE_INVENTORY.json), оба byte-exact манифеста [`RASTER_DERIVATION_MANIFEST.json`](../legal/RASTER_DERIVATION_MANIFEST.json) и [`IMAGEGEN_DERIVATION_MANIFEST.json`](../legal/IMAGEGEN_DERIVATION_MANIFEST.json), а также список неканонических исторических slicer-ов [`NONCANONICAL_IMAGEGEN_SLICERS.md`](../legal/NONCANONICAL_IMAGEGEN_SLICERS.md) по процедуре [`ASSET_RIGHTS_REVIEW.md`](../legal/ASSET_RIGHTS_REVIEW.md). Заполнить внешний [`ASSET_RIGHTS_EVIDENCE_TEMPLATE.json`](../legal/ASSET_RIGHTS_EVIDENCE_TEMPLATE.json) и передать его точные bytes/SHA-256 через `V_SLOT_ASSET_RIGHTS_EVIDENCE_FILE` и `V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256`. Установить `V_SLOT_ASSET_RIGHTS_REVIEWED_VERSION_CODE` только после проверки изображений, названий, музыки, звуков и обязательных notices для текущего commit/version.

## 3. Policy и позиционирование social casino

- [ ] Зарегистрировать приложение как **Game**; предварительная категория: **Casino**. Проверить доступную категорию в текущем Play Console перед отправкой.
- [ ] В short/full description сохранить явные русские формулировки: симулятор слотов, 18+, виртуальные монеты, без игры на реальные деньги, без покупок, без денежных и материальных призов. В выбранном наборе screenshots должен присутствовать реальный экран приложения с теми же ограничениями; не добавлять рекламные или policy-надписи поверх снятых кадров.
- [ ] Не использовать формулировки `заработать`, `вывести`, `получить деньги`, `денежный jackpot`, изображения наличных/платежей или CTA на внешние казино.
- [ ] Не добавлять real-money wagering, покупаемую валюту, денежные/материальные награды, transfer/trading, loyalty rewards с реальной ценностью или ссылки на gambling services без новой юридической и store-policy проверки. Google запрещает нелицензированную механику, где деньги или купленные за деньги предметы ставятся ради приза реальной стоимости: [Real-Money Gambling, Games, and Contests](https://support.google.com/googleplay/android-developer/answer/9877032/).
- [ ] Не добавлять gambling-рекламу. Политика Google отдельно запрещает gambling ads внутри social-casino/virtual-slot приложений: [Ads for Gambling requirements](https://support.google.com/googleplay/android-developer/answer/17105854?hl=en#gambling_ads).
- [ ] Проверить законность simulated gambling и требования к маркировке 18+ для России непосредственно перед отправкой. Добавление любой другой страны блокирует релиз до отдельной legal/policy/privacy проверки этой страны.

## 4. Play Console: App content

- [ ] **Privacy policy:** вставить тот же production URL, который собран в приложении.
- [ ] **Ads:** ответить `No` только после проверки release AAB и dependency graph; в текущем коде ads SDK и рекламных показов нет.
- [ ] **App access:** аккаунта нет. В review instructions описать первый запуск: прочитать экран 18+, подтвердить отсутствие реальной ценности виртуальных монет, затем открыть слот. Не называть checkbox самостоятельной проверкой возраста и не указывать фиктивные credentials.
- [ ] **Target audience and content:** выбрать только группу `18 and over`, затем на том же экране включить `Restrict Minor Access`. Не выбирать детские группы и не вступать в Families/Teacher Approved. При включённом ограничении пользователи, которых Google определяет как несовершеннолетних, не смогут найти или установить приложение; одно только внутреннее предупреждение `18+` этого не заменяет. Сохранить screenshot/экспорт финального состояния Play Console в release evidence: [Target audience guidance](https://support.google.com/googleplay/android-developer/answer/9867159?hl=en).
- [ ] **Content rating:** заполнить IARC честно: simulated gambling/virtual slots присутствуют; real-money gambling, purchases и cash prizes отсутствуют. Не назначать рейтинг вручную и не предполагать, что in-app `18+` автоматически равен рейтингу IARC: [Content rating requirements](https://support.google.com/googleplay/android-developer/answer/9859655?hl=en).
- [ ] **Data safety:** перенести только финально подтверждённые ответы из [DATA_SAFETY_DRAFT.md](DATA_SAFETY_DRAFT.md). SDK-данные тоже входят в декларацию. Сохранить preview/CSV, network capture и snapshot privacy policy в одном evidence archive, внести их SHA-256 в version-bound sign-off JSON и только затем подтвердить текущий релиз через `V_SLOT_DATA_SAFETY_REVIEWED_VERSION_CODE`: [Google Play Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).
- [ ] **Account deletion:** указать, что приложение не позволяет создать аккаунт. Это не заменяет отдельный ответ Data safety о механизме удаления собранной telemetry.
- [ ] **Developer profile:** до отправки проверить в Play Console тип аккаунта, подтверждённые юридическое имя, адрес, email и телефон, а для организации также согласованность профиля и D-U-N-S. Публичные контакты должны совпадать с listing и privacy policy. Повторить проверку после вступления обновлённых требований в силу 30 сентября 2026 года: [Play Console Requirements](https://support.google.com/googleplay/android-developer/answer/10788890?hl=en).
- [ ] Для остальных карточек в `Policy and programs > App content` отвечать по фактическому релизу. На текущем scope: нет financial features, health, news, government affiliation, ads и high-risk permissions. Перепроверить весь раздел `Needs attention` в день отправки: [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en-EN).

## 5. Store listing и страны

- [ ] Использовать и вычитать подготовленный русский listing из [`store-listing-ru.json`](store-listing-ru.json); перед отправкой повторно проверить лимиты Play Console и соответствие финальному release build.
- [ ] Название, short description, full description, feature graphic и screenshots соответствуют реальному release build и не обещают будущие функции. Загружать 32-bit RGBA-иконку из [`assets/v-slot-icon-512-v2.png`](assets/v-slot-icon-512-v2.png), 1024 x 500 RGB feature graphic из [`assets/v-slot-feature-graphic-1024x500-v1.png`](assets/v-slot-feature-graphic-1024x500-v1.png) и сверить их с release build.
- [ ] Добавить подготовленные alt-тексты feature graphic и пяти кадров из [`store-listing-ru.json`](store-listing-ru.json); каждый текст должен оставаться не длиннее 140 символов.
- [ ] Перед фиксацией release commit переснять пять экранов из release-like QA build на Android 36 командой `tools/capture_play_store_screenshots.sh EMULATOR_SERIAL`, визуально проверить [`assets/screenshots`](assets/screenshots) и выбрать минимум два без debug/system UI и персональных данных. Закоммитить PNG и `capture-metadata.json` вместе, затем на чистом release `HEAD` выполнить `verifyStoreScreenshotsAgainstQaApk`: задача должна подтвердить канонические payload SHA-256 заново собранных QA app и instrumentation APK, а `verifyStoreReadiness` — неизменность всех screenshot-файлов относительно `HEAD`.
- [ ] На screenshots нет debug/QA UI, персональных данных, чужих брендов, системных уведомлений с тестовым текстом и изображений, намекающих на реальные выплаты.
- [ ] Указать в Play Console проверенные `V_SLOT_SUPPORT_EMAIL` и `V_SLOT_DEVELOPER_LEGAL_NAME`, рабочий support site и то же юридическое имя в privacy policy.
- [ ] Версия 1.0.0 намеренно поддерживает только русский интерфейс (`ru`) и `ru-RU` listing. Добавлять другие локали, listing и privacy policy только после полноценного перевода и проверки носителем; не включать автоматические переводы как поддерживаемые языки.
- [ ] В `Production > Countries/regions` выбрать только Россию; `Rest of world` и все остальные страны должны быть отключены. Отдельно проверить country targeting каждого open/closed track: доступность определяется страной аккаунта Google Play, а не текущим местоположением пользователя: [Distribute app releases to specific countries](https://support.google.com/googleplay/android-developer/answer/7550024?hl=en-GB).
- [ ] Цена: `Free`. Не включать Play Billing и не объявлять in-app purchases при текущем scope. Бесплатные приложения можно публиковать и скачивать в России, тогда как покупки приложений и цифровых товаров через Google Play для пользователей в России приостановлены: [Google Play billing in Russia](https://support.google.com/googleplay/android-developer/answer/11950272?hl=ru).

## 6. Release artifact

- [ ] Перед каждым релизом увеличить `versionCode`; `versionName` должен совпадать с release notes и архивом symbols/mapping.
- [ ] Убедиться, что актуальный target API соответствует текущему правилу Google. До 30 августа 2026 включительно для новых приложений и обновлений требуется API 35+, с 31 августа 2026 — API 36; проект уже использует API 36: [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-EN).
- [ ] Выполнить на чистом checkout с production secrets:

```bash
./gradlew clean :app:verifyStoreRelease :app:bundleRelease
```

- [ ] Проверить, что AAB подписан ожидаемым upload key и каждая немета-запись после полного чтения содержит ожидаемый сертификат; package равен `com.vslot.app`, build не debuggable, shrink/minify включены, а debug/QA components отсутствуют.
- [ ] Сохранить `bundletool-validation.txt` и `bundletool-base-manifest.xml`: CI обязан скачать bundletool 1.18.3, проверить закреплённый SHA-256, выполнить `validate`/`dump manifest` для этого же signed AAB и подтвердить package, versionCode/versionName, minSdk/targetSdk и отсутствие debug/QA-компонентов.
- [ ] Сохранить `release-16k-page-size.txt`: CI обязан собрать universal APK из этого же AAB, выполнить `zipalign -c -P 16` и проверить `LOAD` alignment каждой `.so` закрепленным `llvm-readelf` из NDK 27.0.12077973.
- [ ] Проверить merged manifest release AAB: ожидаются `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, а также транзитивные `WAKE_LOCK` и `com.google.android.c2dm.permission.RECEIVE` для FCM и `com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE` для install referrer. `AD_ID`, location, billing и storage permissions должны отсутствовать.
- [ ] Проверить release dependency graph: без ads, billing, AppMetrica identifiers/location/app-set-id/id-sync/ad-revenue модулей и без неожиданной Firebase Analytics.
- [ ] Проверить Play App Signing и безопасно сохранить upload key: [Google Play app setup and signing](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en).
- [ ] Архивировать AAB, `release-artifact-evidence.txt`, dependency lock/verification state, R8 mapping и release notes для точного `versionCode`. Загрузить соответствующий `mapping.txt` в Play Console и AppMetrica только если crash reporting будет отдельно включён и задекларирован.
- [ ] Проверить отсутствие несовместимых native libraries и предупреждений о 16 KB page size в Play Console; требования и способы проверки описаны в [Android 16 KB page-size guide](https://developer.android.com/guide/practices/page-sizes).
- [ ] Просмотреть `THIRD_PARTY_NOTICES.md`; отдельно сгенерировать inventory всех фактически упакованных transitive dependencies и закрыть их notice/license obligations.

## 7. Предрелизная проверка

- [ ] Пройти fresh install, upgrade, cold/warm start, process death, offline/online, background/foreground, rotation, dark/system settings, font scale и reduced-motion сценарии.
- [ ] Проверить математику, списание виртуальной ставки, settlement после process death, autospin, free spins, insufficient balance, daily bonus и доступность paytable/disclaimer.
- [ ] Проверить audio focus, silent/vibrate mode, Bluetooth/проводные наушники, входящий звонок и отсутствие фонового звука после ухода из приложения.
- [ ] Проверить аналитику четырьмя сценариями: fresh install без выбора, decline, opt-in, revoke. До opt-in и после revoke не должно быть исходящей AppMetrica telemetry.
- [ ] Проверить push: defer, deny, allow, выключение в Android Settings, token deletion/re-registration, доставку и открытие. Текст push только про daily bonus или обновления игры.
- [ ] Выполнить network capture release build: подтвердить отсутствие cleartext, AD_ID/location, неожиданных endpoints и PII в custom events.
- [ ] Запустить Google Play pre-launch report на поддерживаемых форм-факторах и закрыть crash/ANR/accessibility/security findings.
- [ ] Загрузить тот же AAB без пересборки в internal/closed track, установить его через Google Play на физический Samsung и зафиксировать `versionCode`, Play app-signing certificate и список доставленных split APK. Только этот прогон проверяет Play-delivered binary.
- [ ] Если developer account подпадает под обязательное closed testing для новых personal accounts, выполнить требования, показанные в Play Console; Google отмечает, что они применяются к personal accounts, созданным после 2023-11-13: [Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en).

## 8. Samsung: только QA-устройство

- [ ] Выполнить release-like `qa` connected tests на физическом Samsung с явным serial согласно `README.md`.
- [ ] Проверить Samsung-specific lifecycle, rotation, sound/silent mode, notification permission, battery behavior и FCM delivery.
- [ ] Сохранить сгенерированный `qa/screenshots/evidence/*.json`: он фиксирует обезличенный serial hash, модель, Android/One UI, locale, display/font scale, APK SHA-256, обе landscape-ориентации и результат прогона.
- [ ] Получать raw XML/log evidence из доверенного device-lab job и связывать его attestation с commit и APK payload SHA-256; загруженный вручную ZIP подтверждает целостность байтов, но не происхождение от физического устройства.

**Samsung в этом проекте является QA-устройством. Это не означает намерение или готовность к публикации в Galaxy Store.** Публикация в Galaxy Store является отдельным каналом: нужны отдельная policy/legal/privacy/data-disclosure проверка, Seller Portal metadata, signing/distribution процедура и проверка стран. Нельзя переиспользовать этот Google Play checklist как доказательство готовности к Galaxy Store. Актуальный первичный источник Samsung: [Galaxy Store App Distribution Guide](https://developer.samsung.com/galaxy-store/distribution-guide.html?lang=en).

## 9. Rollout и post-release

- [ ] Сначала internal/closed track, затем ограниченный production rollout; не переходить к 100% при новых crash/ANR, data-safety или gameplay-settlement сигналах.
- [ ] Подготовить rollback: предыдущий стабильный AAB, mapping, конфигурация SDK, store text и список критических метрик.
- [ ] В первые 24/72 часа контролировать Android vitals, reviews, AppMetrica analytics и push errors без включения новых data flows.
- [ ] Любое добавление SDK, permission, endpoint, ads, billing, account, UGC или новой награды блокирует следующий релиз до повторного privacy/Data safety/policy review.

## Финальный sign-off

| Область | Владелец | Дата | Evidence | Решение |
|---|---|---|---|---|
| Product / no-real-money scope |  |  |  |  |
| Legal / countries / age |  |  |  |  |
| Privacy / Data safety |  |  |  |  |
| Security / signing / supply chain |  |  |  |  |
| QA / physical Samsung |  |  |  |  |
| Store listing / IARC |  |  |  |  |
| Release owner |  |  |  |  |
